package com.reewild.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Base64;


@Service
public class CarbonEstimationService {

 private static final Logger log = LoggerFactory.getLogger(CarbonEstimationService.class);

 @Autowired
 private WebClient openAIClient;

 @Autowired
 private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

 @Value("${openai.model}")
 private String model;

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


 public Map<String, Object> estimateFromDishName(String dishName) {
  String prompt = String.format(
       "List the main ingredients in '%s' as a JSON array of strings. " +
            "Only return the JSON array, no extra text. Example: [\"rice\", \"chicken\", \"spices\"]",
       dishName
  );

  try {
   log.info("Calling OpenAI to infer ingredients for dish: {}", dishName);

   String response = openAIClient.post()
        .uri("/chat/completions")
        .bodyValue(Map.of(
             "model", model,
             "messages", List.of(
                  Map.of("role", "user", "content", prompt)
             ),
             "max_tokens", 200
        ))
        .retrieve()
        .bodyToMono(String.class)
        .block();

   if (response == null) {
    log.error("OpenAI returned null response for dish: {}", dishName);
    return fallbackResponse(dishName, "Empty response from OpenAI");
   }

   JsonNode root = objectMapper.readTree(response);
   JsonNode contentNode = root.at("/choices/0/message/content");
   if (contentNode.isMissingNode()) {
    log.error("No content found in OpenAI response for dish: {}", dishName);
    return fallbackResponse(dishName, "Invalid OpenAI response format");
   }

   String content = contentNode.asText().trim();
   JsonNode arrayNode = objectMapper.readTree(content);

   List<Map<String, Object>> ingredients = new ArrayList<>();
   double totalCarbon = 0.0;

   if (arrayNode.isArray()) {
    for (JsonNode node : arrayNode) {
     String name = node.asText().toLowerCase().trim();
     if (name.isEmpty()) continue;

     double carbonKg = CARBON_DB.getOrDefault(name, 0.5);
     totalCarbon += carbonKg;

     Map<String, Object> ingredient = new HashMap<>();
     ingredient.put("name", capitalize(name));
     ingredient.put("carbon_kg", Math.round(carbonKg * 10.0) / 10.0);
     ingredients.add(ingredient);
    }
   } else {
    log.warn("LLM did not return a valid JSON array for dish: {}", dishName);
   }

   Map<String, Object> result = new HashMap<>();
   result.put("dish", capitalize(dishName));
   result.put("estimated_carbon_kg", Math.round(totalCarbon * 10.0) / 10.0);
   result.put("ingredients", ingredients);

   log.info("Successfully estimated carbon footprint for '{}': {} kg CO₂", dishName, result.get("estimated_carbon_kg"));
   return result;

  } catch (Exception e) {
   log.error("Failed to estimate carbon footprint for dish: {}", dishName, e);
   return fallbackResponse(dishName, e.getMessage());
  }
 }


 public Map<String, Object> estimateFromImage(byte[] imageBytes, String mimeType) {
  if (imageBytes == null || imageBytes.length == 0) {
   log.warn("Received empty image data");
   return fallbackResponse("Unknown Dish", "Empty image uploaded");
  }


  if (mimeType == null || !mimeType.startsWith("image/")) {
   log.info("MIME type not provided or invalid. Defaulting to image/jpeg");
   mimeType = "image/jpeg";
  }

  String base64Image = Base64.getEncoder().encodeToString(imageBytes);
  String dataUri = "data:" + mimeType + ";base64," + base64Image;

  String content = String.format(
       "[{\"type\": \"text\", \"text\": \"What food is this? Respond only with the dish name.\"}," +
            "{\"type\": \"image_url\", \"image_url\": {\"url\": \"%s\"}}]",
       dataUri
  );

  try {
   log.info("Sending image to GPT-4 Vision for dish identification");

   String response = openAIClient.post()
        .uri("/chat/completions")
        .bodyValue(Map.of(
             "model", "gpt-4o",
             "messages", List.of(
                  Map.of("role", "user", "content", content)
             ),
             "max_tokens", 100
        ))
        .retrieve()
        .bodyToMono(String.class)
        .block();

   if (response == null) {
    log.error("GPT-4 Vision returned null response");
    return fallbackResponse("Unknown Dish", "Empty response from vision model");
   }

   JsonNode root = objectMapper.readTree(response);
   JsonNode contentNode = root.at("/choices/0/message/content");
   if (contentNode.isMissingNode()) {
    log.error("No content found in GPT-4 Vision response");
    return fallbackResponse("Unknown Dish", "Invalid vision response format");
   }

   String dishName = contentNode.asText().trim();
   log.info("GPT-4 Vision identified dish: {}", dishName);
   return estimateFromDishName(dishName);

  } catch (Exception e) {
   log.error("Failed to process image with GPT-4 Vision", e);
   return fallbackResponse("Unknown Dish", e.getMessage());
  }
 }


 private String capitalize(String word) {
  if (word == null || word.isEmpty()) return word;
  return word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase();
 }


 private Map<String, Object> fallbackResponse(String dish, String error) {
  List<Map<String, Object>> ingredients = List.of(
       Map.of("name", "Unknown", "carbon_kg", 1.0)
  );
  Map<String, Object> result = new HashMap<>();
  result.put("dish", dish);
  result.put("estimated_carbon_kg", 1.0);
  result.put("ingredients", ingredients);
  result.put("error", error != null ? error : "Unknown error");
  return result;
 }


 private Map<String, Object> fallbackResponse(String dish) {
  return fallbackResponse(dish, null);
 }
}