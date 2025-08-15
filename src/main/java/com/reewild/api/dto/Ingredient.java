package com.reewild.api.dto;

public class Ingredient {
 private String name;
 private double carbonKg;

 public Ingredient() {}

 public Ingredient(String name, double carbonKg) {
  this.name = name;
  this.carbonKg = carbonKg;
 }

 public String getName() {
  return name;
 }

 public double getCarbonKg() {
  return carbonKg;
 }

 public void setName(String name) {
  this.name = name;
 }

 public void setCarbonKg(double carbonKg) {
  this.carbonKg = carbonKg;
 }
}
