package com.payguard.chaosinjector.service;

import com.payguard.chaosinjector.domain.ChaosConfig;
import com.payguard.chaosinjector.domain.ChaosMode;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory chaos state, live-toggleable via {@code PUT /chaos/config} rather than requiring a
 * ConfigMap edit + pod restart cycle (PHASE_7_DESIGN.md Decision 2). The initial state on pod start
 * comes from the {@code CHAOS_MODE} env var (default {@code NONE}), consistent with the project's
 * existing ConfigMap-sourced config pattern; everything after startup goes through this service
 * instead.
 */
@Component
public class ChaosConfigService {

  private final AtomicReference<ChaosConfig> current;

  public ChaosConfigService(@Value("${payguard.chaos.default-mode:NONE}") String defaultMode) {
    this.current = new AtomicReference<>(ChaosConfig.defaultFor(ChaosMode.valueOf(defaultMode)));
  }

  public ChaosConfig get() {
    return current.get();
  }

  public ChaosConfig update(ChaosConfig newConfig) {
    current.set(newConfig);
    return newConfig;
  }
}
