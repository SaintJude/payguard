package com.payguard.chaosinjector.dto;

/**
 * Request body for {@code PUT /chaos/config}. {@code mode} is a string here (rather than the {@code
 * ChaosMode} enum directly) so an unrecognized value can be turned into a clean {@code 400} by the
 * controller instead of a raw JSON-deserialization error. {@code latencyMs}/ {@code probabilityPct}
 * are optional — a caller only needs to set the one param relevant to the mode it's choosing (see
 * {@code ChaosConfig}'s javadoc for which mode uses which param); omitted values default to {@code
 * 0}.
 */
public record ChaosConfigRequest(String mode, Integer latencyMs, Integer probabilityPct) {}
