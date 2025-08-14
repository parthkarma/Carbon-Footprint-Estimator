package com.reewild.api.controller;

import com.reewild.api.dto.CarbonEstimateResponse;
import com.reewild.api.dto.DishRequest;
import com.reewild.api.service.CarbonEstimationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CarbonEstimateController {

 private final CarbonEstimationService estimationService;

 @Autowired
 public CarbonEstimateController(CarbonEstimationService estimationService) {
  this.estimationService = estimationService;
  System.out.println("CarbonEstimateController loaded!");
 }

 @PostMapping("/estimate")
 public ResponseEntity<CarbonEstimateResponse> estimateCarbon(@RequestBody DishRequest request) {
  CarbonEstimateResponse response = (CarbonEstimateResponse) estimationService.estimateFromDishName(request.getDish());
  return ResponseEntity.ok(response);
 }
}
