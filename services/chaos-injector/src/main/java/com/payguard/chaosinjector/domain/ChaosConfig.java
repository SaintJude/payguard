package com.payguard.chaosinjector.domain;

/**
 * The full state backing {@code GET}/{@code PUT /chaos/config}: one active mode plus its two
 * numeric params (not all modes use both — {@code LATENCY} uses {@code latencyMs}; {@code
 * ERROR_5XX}/{@code ERROR_4XX} use {@code probabilityPct}; {@code NONE}/{@code DROP} use neither).
 * Deliberately a single flat record, not a rules engine or per-mode config tree — see
 * PHASE_7_DESIGN.md Decision 2's "don't over-engineer" framing.
 */
public record ChaosConfig(ChaosMode mode, int latencyMs, int probabilityPct) {

  public static ChaosConfig defaultFor(ChaosMode mode) {
    return new ChaosConfig(mode, 0, 0);
  }
}
