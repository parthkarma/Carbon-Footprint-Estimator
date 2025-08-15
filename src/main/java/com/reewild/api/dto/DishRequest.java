package com.reewild.api.dto;

import jakarta.validation.constraints.NotBlank;

public class DishRequest {

 @NotBlank(message = "dish must not be blank")
 private String dish;

 public DishRequest() {}

 public DishRequest(String dish) {
  this.dish = dish;
 }

 public String getDish() {
  return dish;
 }

 public void setDish(String dish) {
  this.dish = dish;
 }
}
