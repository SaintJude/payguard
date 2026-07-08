package com.payguard.chaosinjector.util;

/**
 * A tiny seam around blocking-sleep so {@code ProcessController}'s {@code LATENCY}/{@code DROP}
 * fault modes can be exercised in unit tests without a test actually waiting out a real delay —
 * tests substitute a mock implementation and assert it was invoked with the expected duration,
 * instead of racing a wall-clock sleep.
 */
public interface Sleeper {

  /** Blocks the current thread for {@code millis} milliseconds. A non-positive value is a no-op. */
  void sleep(long millis);
}
