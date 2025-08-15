package com.reewild.api.dto;

import java.util.List;

public class CarbonEstimateResponse {
 private String dish;
 private double estimatedCarbonKg;
 private List<Ingredient> ingredients;

 // Optional error to surface graceful failures
 private String error;

 public CarbonEstimateResponse() {}

 public CarbonEstimateResponse(String dish, double estimatedCarbonKg, List<Ingredient> ingredients, String error) {
  this.dish = dish;
  this.estimatedCarbonKg = estimatedCarbonKg;
  this.ingredients = ingredients;
  this.error = error;
 }

 public String getDish() {
  return dish;
 }

 public void setDish(String dish) {
  this.dish = dish;
 }

 public double getEstimatedCarbonKg() {
  return estimatedCarbonKg;
 }

 public void setEstimatedCarbonKg(double estimatedCarbonKg) {
  this.estimatedCarbonKg = estimatedCarbonKg;
 }

 public List<Ingredient> getIngredients() {
  return ingredients;
 }

 public void setIngredients(List<Ingredient> ingredients) {
  this.ingredients = ingredients;
 }

 public String getError() {
  return error;
 }

 public void setError(String error) {
  this.error = error;
 }
}
