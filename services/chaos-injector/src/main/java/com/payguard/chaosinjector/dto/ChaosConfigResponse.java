package com.payguard.chaosinjector.dto;

import com.payguard.chaosinjector.domain.ChaosConfig;

public record ChaosConfigResponse(String mode, int latencyMs, int probabilityPct) {

  public static ChaosConfigResponse from(ChaosConfig config) {
    return new ChaosConfigResponse(
        config.mode().name(), config.latencyMs(), config.probabilityPct());
  }
}
