package com.payguard.chaosinjector.controller;

import com.payguard.chaosinjector.domain.ChaosConfig;
import com.payguard.chaosinjector.domain.ChaosMode;
import com.payguard.chaosinjector.dto.ChaosConfigRequest;
import com.payguard.chaosinjector.dto.ChaosConfigResponse;
import com.payguard.chaosinjector.service.ChaosConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live chaos toggle admin surface (PHASE_7_DESIGN.md Decision 2). Chosen over a ConfigMap edit
 * + rollout-restart cycle specifically so a chaos demo can flip a fault on, watch it happen, and
 * flip it back off within seconds.
 */
@RestController
@RequestMapping("/chaos/config")
public class ChaosConfigController {

  private final ChaosConfigService chaosConfigService;

  public ChaosConfigController(ChaosConfigService chaosConfigService) {
    this.chaosConfigService = chaosConfigService;
  }

  @GetMapping
  public ResponseEntity<ChaosConfigResponse> get() {
    return ResponseEntity.ok(ChaosConfigResponse.from(chaosConfigService.get()));
  }

  @PutMapping
  public ResponseEntity<ChaosConfigResponse> update(@RequestBody ChaosConfigRequest request) {
    ChaosMode mode;
    try {
      mode = ChaosMode.valueOf(request.mode());
    } catch (IllegalArgumentException | NullPointerException ex) {
      return ResponseEntity.badRequest().build();
    }

    ChaosConfig updated =
        new ChaosConfig(
            mode,
            request.latencyMs() != null ? request.latencyMs() : 0,
            request.probabilityPct() != null ? request.probabilityPct() : 0);

    return ResponseEntity.ok(ChaosConfigResponse.from(chaosConfigService.update(updated)));
  }
}
