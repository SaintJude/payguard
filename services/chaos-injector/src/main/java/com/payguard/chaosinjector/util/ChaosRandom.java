package com.payguard.chaosinjector.util;

/**
 * A tiny seam around randomness so {@code ProcessController}'s {@code ERROR_5XX}/{@code ERROR_4XX}
 * probability-based short-circuiting (PHASE_7_DESIGN.md Decision 2) can be exercised
 * deterministically in unit tests — tests substitute a mock implementation instead of depending on
 * real randomness to hit both branches reliably.
 */
public interface ChaosRandom {

  /** Returns a uniformly distributed integer in {@code [0, 100)}. */
  int nextPercent();
}
