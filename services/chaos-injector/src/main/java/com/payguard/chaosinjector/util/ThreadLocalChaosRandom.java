package com.payguard.chaosinjector.util;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Production {@link ChaosRandom}: backed by {@link ThreadLocalRandom}. */
@Component
public class ThreadLocalChaosRandom implements ChaosRandom {

  @Override
  public int nextPercent() {
    return ThreadLocalRandom.current().nextInt(100);
  }
}
