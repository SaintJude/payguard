package com.payguard.chaosinjector.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.payguard.chaosinjector.domain.ChaosConfig;
import com.payguard.chaosinjector.domain.ChaosMode;
import org.junit.jupiter.api.Test;

class ChaosConfigServiceTest {

  @Test
  void defaultsToConfiguredModeWithZeroedParams() {
    ChaosConfigService service = new ChaosConfigService("NONE");

    assertThat(service.get()).isEqualTo(new ChaosConfig(ChaosMode.NONE, 0, 0));
  }

  @Test
  void updateReplacesCurrentStateAndReturnsIt() {
    ChaosConfigService service = new ChaosConfigService("NONE");
    ChaosConfig updated = new ChaosConfig(ChaosMode.LATENCY, 750, 0);

    ChaosConfig result = service.update(updated);

    assertThat(result).isEqualTo(updated);
    assertThat(service.get()).isEqualTo(updated);
  }
}
