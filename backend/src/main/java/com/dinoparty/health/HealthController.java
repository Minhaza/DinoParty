package com.dinoparty.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
class HealthController {

  private final HealthService healthService;

  HealthController(HealthService healthService) {
    this.healthService = healthService;
  }

  @GetMapping
  ResponseEntity<HealthResponse> health() {
    HealthResponse response = healthService.check();
    HttpStatus status =
        HealthResponse.UP.equals(response.status())
            ? HttpStatus.OK
            : HttpStatus.SERVICE_UNAVAILABLE;
    return ResponseEntity.status(status).body(response);
  }
}
