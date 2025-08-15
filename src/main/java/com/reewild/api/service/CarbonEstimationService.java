package com.reewild.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reewild.api.dto.CarbonEstimateResponse;
import com.reewild.api.dto.Ingredient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CarbonEstimationService {

 private static final Logger log = LoggerFactory.getLogger(CarbonEstimationService.class);

 private final WebClient openAIClient;
 private final ObjectMapper objectMapper;

 // Rate limiting components
 private final Semaphore rateLimitSemaphore;
 private final AtomicLong lastRequestTime = new AtomicLong(0);
 private final Map<String, Object> imageCache = new ConcurrentHashMap<>();

 // Rate limiting configuration
 private static final long MIN_REQUEST_INTERVAL_MS = 20000; // 20 seconds between requests for free tier
 private static final int MAX_CONCURRENT_REQUESTS = 1; // Only 1 concurrent request for free tier

 @Value("${openai.model}")
 private String model;

 @Value("${openai.vision-model:gpt-4o-mini}")
 private String visionModel;

 @Value("${openai.rate-limit.enabled:true}")
 private boolean rateLimitEnabled;

 @Value("${openai.rate-limit.min-interval-ms:20000}")
 private long minIntervalMs;

 public CarbonEstimationService(WebClient openAIClient, ObjectMapper objectMapper) {
  this.openAIClient = openAIClient;
  this.objectMapper = objectMapper;
  this.rateLimitSemaphore = new Semaphore(MAX_CONCURRENT_REQUESTS, true);
 }

 // Mock carbon database: ingredient → kg CO₂ per typical serving
 private static final Map<String, Double> CARBON_DB = Map.ofEntries(
      Map.entry("chicken", 2.5),
      Map.entry("rice", 1.1),
      Map.entry("beef", 6.0),
      Map.entry("pork", 3.8),
      Map.entry("lamb", 5.5),
      Map.entry("tofu", 0.2),
      Map.entry("cheese", 3.0),
      Map.entry("milk", 0.6),
      Map.entry("butter", 1.0),
      Map.entry("oil", 0.4),
      Map.entry("spices", 0.2),
      Map.entry("onion", 0.05),
      Map.entry("garlic", 0.03),
      Map.entry("tomato", 0.1),
      Map.entry("potato", 0.07),
      Map.entry("egg", 0.5),
      Map.entry("noodles", 0.9),
      Map.entry("vegetables", 0.1),
      Map.entry("beans", 0.3),
      Map.entry("lentils", 0.2)
 );

 /**
  * Rate limiting wrapper for OpenAI API calls
  */
 private <T> Mono<T> withRateLimit(Mono<T> operation) {
  return Mono.fromCallable(() -> {
        if (!rateLimitEnabled) {
         return null; // Skip rate limiting if disabled
        }

        // Acquire semaphore permit
        rateLimitSemaphore.acquire();

        try {
         // Enforce minimum interval between requests
         long currentTime = System.currentTimeMillis();
         long timeSinceLastRequest = currentTime - lastRequestTime.get();
         long actualMinInterval = Math.max(minIntervalMs, MIN_REQUEST_INTERVAL_MS);

         if (timeSinceLastRequest < actualMinInterval) {
          long waitTime = actualMinInterval - timeSinceLastRequest;
          log.info("Rate limiting: waiting {}ms before next OpenAI request", waitTime);
          Thread.sleep(waitTime);
         }

         lastRequestTime.set(System.currentTimeMillis());
         return null;

        } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new RuntimeException("Rate limiting interrupted", e);
        } finally {
         rateLimitSemaphore.release();
        }
       })
       .then(operation);
 }

 /**
  * Enhanced retry strategy for OpenAI API calls
  */
 private Retry createEnhancedRetry() {
  return Retry.backoff(5, Duration.ofSeconds(5)) // Increased retries and initial delay
       .maxBackoff(Duration.ofMinutes(2)) // Max 2 minute backoff
       .filter(throwable -> {
        if (throwable instanceof WebClientResponseException wcre) {
         int statusCode = wcre.getStatusCode().value();
         // Retry on rate limiting (429) and server errors (5xx)
         return statusCode == 429 || statusCode >= 500;
        }
        return false;
       })
       .doBeforeRetry(retrySignal -> {
        Throwable failure = retrySignal.failure();
        long attempt = retrySignal.totalRetries() + 1;
        Duration delay = retrySignal.totalRetriesInARow() == 0 ? Duration.ZERO :
             Duration.ofSeconds((long) Math.pow(2, attempt) * 5); // Exponential backoff

        if (failure instanceof WebClientResponseException wcre) {
         log.warn("OpenAI API call failed (attempt {}), retrying in {}ms. Status: {}, Error: {}",
              attempt, delay.toMillis(), wcre.getStatusCode(), wcre.getResponseBodyAsString());
        } else {
         log.warn("OpenAI API call failed (attempt {}), retrying in {}ms. Error: {}",
              attempt, delay.toMillis(), failure.getMessage());
        }
       })
       .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
        Throwable lastFailure = retrySignal.failure();
        log.error("All retry attempts exhausted for OpenAI API call. Last error: {}", lastFailure.getMessage());
        return new RuntimeException("OpenAI API call failed after all retry attempts", lastFailure);
       });
 }

 public CarbonEstimateResponse estimateFromDishName(String dishName) {
  String prompt = """
                You are a strict JSON generator.
                Given a dish name, return ONLY a JSON array of its likely main ingredients (strings).
                No prose, no backticks, no explanations.
                Example output: ["rice","chicken","spices"]
                Dish: %s
                """.formatted(dishName);

  try {
   log.info("Calling OpenAI to infer ingredients for dish: {}", dishName);

   Mono<String> apiCall = openAIClient.post()
        .uri("/chat/completions")
        .bodyValue(Map.of(
             "model", model,
             "messages", List.of(Map.of("role", "user", "content", prompt)),
             "max_tokens", 200,
             "temperature", 0
        ))
        .retrieve()
        .onStatus(HttpStatusCode::isError, resp -> resp.createException().flatMap(e -> {
         log.error("OpenAI error {}: {}", resp.statusCode(), e.getMessage());
         return Mono.error(e);
        }))
        .bodyToMono(String.class)
        .timeout(Duration.ofSeconds(45)) // Increased timeout
        .retryWhen(createEnhancedRetry());

   String raw = withRateLimit(apiCall).block();

   if (raw == null) {
    return fallback("Unknown", "Empty response from OpenAI");
   }

   List<String> ingredientsList = extractStringArrayFromResponse(raw);
   List<Ingredient> ingredients = new ArrayList<>();
   double total = 0.0;

   for (String s : ingredientsList) {
    String name = s.toLowerCase().trim();
    if (name.isEmpty()) continue;
    double kg = CARBON_DB.getOrDefault(name, 0.5);
    total += kg;
    ingredients.add(new Ingredient(capitalize(name), round1(kg)));
   }

   return new CarbonEstimateResponse(capitalize(dishName), round1(total), ingredients, null);

  } catch (WebClientResponseException wcre) {
   log.error("OpenAI HTTP {}: {}", wcre.getStatusCode(), wcre.getResponseBodyAsString(), wcre);
   String errorMsg = String.format("OpenAI HTTP %d", wcre.getStatusCode().value());
   if (wcre.getStatusCode().value() == 429) {
    errorMsg += " - Rate limit exceeded. Please wait a moment and try again.";
   }
   return fallback(dishName, errorMsg);
  } catch (Exception e) {
   log.error("Failed to estimate from dish name: {}", dishName, e);
   return fallback(dishName, e.getMessage());
  }
 }

 public CarbonEstimateResponse estimateFromImage(byte[] imageBytes, String mimeType) {
  if (imageBytes == null || imageBytes.length == 0) {
   return fallback("Unknown Dish", "Empty image uploaded");
  }

  if (mimeType == null || !mimeType.startsWith("image/")) {
   mimeType = "image/jpeg"; // safe fallback
  }

  // Simple caching based on image hash
  String imageHash = String.valueOf(Arrays.hashCode(imageBytes));
  if (imageCache.containsKey(imageHash)) {
   log.info("Using cached result for image");
   return (CarbonEstimateResponse) imageCache.get(imageHash);
  }

  String base64 = Base64.getEncoder().encodeToString(imageBytes);
  String dataUri = "data:" + mimeType + ";base64," + base64;

  // Use multimodal content format
  var userContent = List.of(
       Map.of("type", "text", "text", "Identify the dish in the image. Reply with ONLY the dish name."),
       Map.of("type", "image_url", "image_url", Map.of("url", dataUri))
  );

  try {
   log.info("Sending image to {} for dish identification", visionModel);

   Mono<String> apiCall = openAIClient.post()
        .uri("/chat/completions")
        .bodyValue(Map.of(
             "model", visionModel,
             "messages", List.of(Map.of("role", "user", "content", userContent)),
             "max_tokens", 50,
             "temperature", 0
        ))
        .retrieve()
        .onStatus(HttpStatusCode::isError, resp -> resp.createException().flatMap(e -> {
         log.error("OpenAI Vision error {}: {}", resp.statusCode(), e.getMessage());
         return Mono.error(e);
        }))
        .bodyToMono(String.class)
        .timeout(Duration.ofSeconds(60)) // Increased timeout for vision
        .retryWhen(createEnhancedRetry());

   String raw = withRateLimit(apiCall).block();

   if (raw == null) {
    return fallback("Unknown Dish", "Empty response from vision model");
   }

   String dishName = readMessageContent(raw).trim();
   if (dishName.isEmpty()) {
    log.warn("Vision model returned empty dish name. Raw response: {}", raw);
    return fallback("Unknown Dish", "Vision model returned empty dish name");
   }

   log.info("Vision model identified dish: '{}'", dishName);
   CarbonEstimateResponse response = estimateFromDishName(dishName);

   // Cache the successful response
   imageCache.put(imageHash, response);

   return response;

  } catch (WebClientResponseException wcre) {
   log.error("OpenAI Vision HTTP {}: {}", wcre.getStatusCode(), wcre.getResponseBodyAsString(), wcre);
   String errorMsg = String.format("OpenAI Vision HTTP %d", wcre.getStatusCode().value());
   if (wcre.getStatusCode().value() == 429) {
    errorMsg += " - Rate limit exceeded. Please wait before trying again.";
   }
   return fallback("Unknown Dish", errorMsg);
  } catch (Exception e) {
   log.error("Unexpected error during image processing", e);
   return fallback("Unknown Dish", "Processing failed: " + e.getMessage());
  }
 }

 private List<String> extractStringArrayFromResponse(String raw) throws Exception {
  JsonNode root = objectMapper.readTree(raw);
  JsonNode contentNode = root.at("/choices/0/message/content");

  if (!contentNode.isMissingNode()) {
   String content = contentNode.asText();
   List<String> arr = tryParseStringArray(content);
   if (!arr.isEmpty()) return arr;
  }

  String rawText = contentNode.isMissingNode() ? raw : contentNode.asText();
  Pattern p = Pattern.compile("\\[(?s).*?\\]");
  Matcher m = p.matcher(rawText);
  if (m.find()) {
   String arrayText = m.group();
   List<String> arr = tryParseStringArray(arrayText);
   if (!arr.isEmpty()) return arr;
  }

  return List.of("rice");
 }

 private List<String> tryParseStringArray(String json) {
  try {
   JsonNode node = objectMapper.readTree(json);
   if (node.isArray()) {
    return objectMapper.convertValue(node, new TypeReference<List<String>>() {});
   }
  } catch (Exception ignored) {}
  return Collections.emptyList();
 }

 private String readMessageContent(String raw) throws Exception {
  JsonNode root = objectMapper.readTree(raw);
  JsonNode contentNode = root.at("/choices/0/message/content");
  return contentNode.isMissingNode() ? "" : contentNode.asText();
 }

 private CarbonEstimateResponse fallback(String dish, String error) {
  return new CarbonEstimateResponse(
       capitalize(dish),
       1.0,
       List.of(new Ingredient("Unknown", 1.0)),
       error == null ? "Unknown error" : error
  );
 }

 private String capitalize(String s) {
  if (s == null || s.isEmpty()) return s;
  return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
 }

 private double round1(double v) {
  return Math.round(v * 10.0) / 10.0;
 }
}