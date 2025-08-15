package com.reewild.api.controller;

import com.reewild.api.dto.CarbonEstimateResponse;
import com.reewild.api.dto.DishRequest;
import com.reewild.api.service.CarbonEstimationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class CarbonEstimateController {

 private final CarbonEstimationService estimationService;

 public CarbonEstimateController(CarbonEstimationService estimationService) {
  this.estimationService = estimationService;
 }
 @PostMapping("/estimate")
 public ResponseEntity<CarbonEstimateResponse> estimateCarbon(@Valid @RequestBody DishRequest request) {
  CarbonEstimateResponse response = (CarbonEstimateResponse) estimationService.estimateFromDishName(request.getDish());
  return ResponseEntity.ok(response);
 }
 @PostMapping(value = "/estimate/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
 public ResponseEntity<CarbonEstimateResponse> estimateFromImage(@RequestParam("file") MultipartFile file) throws Exception {
  if (file == null || file.isEmpty()) {
   return ResponseEntity.badRequest().body(
        new CarbonEstimateResponse("Unknown Dish", 1.0, java.util.List.of(), "No file uploaded")
   );
  }
  String mimeType = file.getContentType();
  byte[] bytes = file.getBytes();
  CarbonEstimateResponse response = (CarbonEstimateResponse) estimationService.estimateFromImage(bytes, mimeType);
  return ResponseEntity.ok(response);
 }
}
