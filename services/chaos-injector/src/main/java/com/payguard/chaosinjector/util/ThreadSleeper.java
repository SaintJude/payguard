package com.payguard.chaosinjector.util;

import org.springframework.stereotype.Component;

/** Production {@link Sleeper}: a real {@link Thread#sleep(long)}. */
@Component
public class ThreadSleeper implements Sleeper {

  @Override
  public void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
