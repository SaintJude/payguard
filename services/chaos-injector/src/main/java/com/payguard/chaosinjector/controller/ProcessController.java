package com.payguard.chaosinjector.controller;

import com.payguard.chaosinjector.domain.ChaosConfig;
import com.payguard.chaosinjector.downstream.MockDownstreamClient;
import com.payguard.chaosinjector.dto.ProcessRequest;
import com.payguard.chaosinjector.dto.ProcessResponse;
import com.payguard.chaosinjector.service.ChaosConfigService;
import com.payguard.chaosinjector.util.ChaosRandom;
import com.payguard.chaosinjector.util.Sleeper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The proxy hop {@code worker} actually calls instead of {@code mock-downstream} directly
 * (PHASE_7_DESIGN.md Decision 2). Wave 3 implements the real fault behavior for all five modes (see
 * Decision 2's fault-modes table and the Contracts' HTTP API table):
 *
 * <ul>
 *   <li>{@code NONE} — forwards to {@code mock-downstream} unmodified, returns its real {@code
 *       200}.
 *   <li>{@code LATENCY} — sleeps {@code latencyMs} before forwarding, then returns
 *       mock-downstream's real response.
 *   <li>{@code ERROR_5XX} — short-circuits with probability {@code probabilityPct}, returning
 *       {@code 503} without ever calling {@code mock-downstream}; otherwise passes through
 *       normally.
 *   <li>{@code ERROR_4XX} — short-circuits with probability {@code probabilityPct}, returning
 *       {@code 400} without ever calling {@code mock-downstream}; otherwise passes through
 *       normally.
 *   <li>{@code DROP} — sleeps past worker's 3s read timeout ({@link #DROP_SLEEP_MS}, 5s per
 *       Contracts) without ever calling {@code mock-downstream}. By the time this method would
 *       otherwise respond, worker has already given up client-side — chaos-injector doesn't need to
 *       do anything special beyond simply not answering within worker's window.
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class ProcessController {

  /**
   * Must exceed worker's 3s read timeout to reliably produce a client-side timeout
   * (PHASE_7_DESIGN.md Contracts: "DROP chaos sleep duration | 5s ... executed by chaos-injector,
   * not mock-downstream").
   */
  static final long DROP_SLEEP_MS = 5_000L;

  private final ChaosConfigService chaosConfigService;
  private final MockDownstreamClient mockDownstreamClient;
  private final ChaosRandom chaosRandom;
  private final Sleeper sleeper;

  public ProcessController(
      ChaosConfigService chaosConfigService,
      MockDownstreamClient mockDownstreamClient,
      ChaosRandom chaosRandom,
      Sleeper sleeper) {
    this.chaosConfigService = chaosConfigService;
    this.mockDownstreamClient = mockDownstreamClient;
    this.chaosRandom = chaosRandom;
    this.sleeper = sleeper;
  }

  @PostMapping("/process")
  public ResponseEntity<ProcessResponse> process(@RequestBody ProcessRequest request) {
    ChaosConfig chaosConfig = chaosConfigService.get();

    switch (chaosConfig.mode()) {
      case NONE -> {
        // Forward unmodified; mock-downstream always succeeds.
      }
      case LATENCY -> {
        // Delay before forwarding, then return mock-downstream's real response.
        sleeper.sleep(chaosConfig.latencyMs());
      }
      case ERROR_5XX -> {
        if (shortCircuit(chaosConfig.probabilityPct())) {
          return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        // Probability missed: fall through to normal passthrough forwarding below.
      }
      case ERROR_4XX -> {
        if (shortCircuit(chaosConfig.probabilityPct())) {
          return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
      }
      case DROP -> {
        sleeper.sleep(DROP_SLEEP_MS);
        // mock-downstream is never called on this branch. Worker's own 3s read timeout has
        // already fired client-side by now; whatever status this method returns is answering a
        // socket the caller has almost certainly already abandoned, so the exact code here isn't
        // load-bearing — the timeout already happened on worker's side.
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
      }
    }

    ProcessResponse response = mockDownstreamClient.process(request);
    return ResponseEntity.ok(response);
  }

  /** {@code true} with probability {@code probabilityPct} (0-100) out of 100. */
  private boolean shortCircuit(int probabilityPct) {
    return chaosRandom.nextPercent() < probabilityPct;
  }
}
