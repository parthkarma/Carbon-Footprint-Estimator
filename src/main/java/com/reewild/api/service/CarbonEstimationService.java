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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CarbonEstimationService {

 private static final Logger log = LoggerFactory.getLogger(CarbonEstimationService.class);

 private final WebClient openAIClient;
 private final ObjectMapper objectMapper;

 @Value("${openai.model}")
 private String model;

 @Value("${openai.vision-model:gpt-4o}")
 private String visionModel;

 public CarbonEstimationService(WebClient openAIClient, ObjectMapper objectMapper) {
  this.openAIClient = openAIClient;
  this.objectMapper = objectMapper;
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

   String raw = openAIClient.post()
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
         return reactor.core.publisher.Mono.error(e);
        }))
        .bodyToMono(String.class)
        .block();

   if (raw == null) {
    return fallback("Unknown", "Empty response from OpenAI");
   }

   // Extract the JSON array robustly
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
   return fallback(dishName, "OpenAI HTTP " + wcre.getStatusCode().value());
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
   // Controller already passes detected content-type. Still keep a safe default.
   mimeType = "image/jpeg";
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

   String raw = openAIClient.post()
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
         return reactor.core.publisher.Mono.error(e);
        }))
        .bodyToMono(String.class)
        .block();

   if (raw == null) {
    return fallback("Unknown Dish", "Empty response from vision model");
   }

   String dishName = readMessageContent(raw).trim();
   if (dishName.isEmpty()) {
    return fallback("Unknown Dish", "Vision model returned empty dish name");
   }
   return estimateFromDishName(dishName);

  } catch (WebClientResponseException wcre) {
   log.error("OpenAI Vision HTTP {}: {}", wcre.getStatusCode(), wcre.getResponseBodyAsString(), wcre);
   return fallback("Unknown Dish", "OpenAI Vision HTTP " + wcre.getStatusCode().value());
  } catch (Exception e) {
   log.error("Failed to process image", e);
   return fallback("Unknown Dish", e.getMessage());
  }
 }

 // ---------- Helpers ----------

 private List<String> extractStringArrayFromResponse(String raw) throws Exception {
  // 1) Parse whole JSON and try to navigate to the content
  JsonNode root = objectMapper.readTree(raw);
  JsonNode contentNode = root.at("/choices/0/message/content");

  if (!contentNode.isMissingNode()) {
   String content = contentNode.asText();
   List<String> arr = tryParseStringArray(content);
   if (!arr.isEmpty()) return arr;
  }

  // 2) As a fallback, regex the first [...] block and parse it
  String rawText = contentNode.isMissingNode() ? raw : contentNode.asText();
  Pattern p = Pattern.compile("\\[(?s).*?\\]");
  Matcher m = p.matcher(rawText);
  if (m.find()) {
   String arrayText = m.group();
   List<String> arr = tryParseStringArray(arrayText);
   if (!arr.isEmpty()) return arr;
  }

  // 3) Last resort: make a single-item guess to avoid hard failure
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
