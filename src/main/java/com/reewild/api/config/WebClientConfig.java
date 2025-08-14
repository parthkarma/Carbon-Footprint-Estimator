package com.reewild.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

 @Value("${openai.base-url}")
 private String openaiBaseUrl;

 @Value("${openai.api-key}")
 private String openaiApiKey;

 @Bean
 public WebClient openAIClient() {
  return WebClient.builder()
       .baseUrl(openaiBaseUrl)
       .defaultHeader("Content-Type", "application/json")
       .defaultHeader("Authorization", "Bearer " + openaiApiKey)
       .build();
 }
}