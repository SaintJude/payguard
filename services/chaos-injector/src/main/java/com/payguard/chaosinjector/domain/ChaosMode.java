package com.payguard.chaosinjector.domain;

/**
 * The five exhaustive fault modes {@code chaos-injector} can be configured into (see
 * PHASE_7_DESIGN.md Decision 2's fault-modes table). Only {@link #NONE} is functionally implemented
 * as of Wave 1 — every other mode is scaffolded here (so the config API and model are final) but
 * falls through to {@code NONE}'s passthrough behavior in {@code ProcessController} until Wave 3
 * implements the real fault logic.
 */
public enum ChaosMode {
  NONE,
  LATENCY,
  ERROR_5XX,
  ERROR_4XX,
  DROP
}
