package com.payguard.chaosinjector.controller;

import com.payguard.chaosinjector.domain.ChaosConfig;
import com.payguard.chaosinjector.downstream.MockDownstreamClient;
import com.payguard.chaosinjector.dto.ProcessRequest;
import com.payguard.chaosinjector.dto.ProcessResponse;
import com.payguard.chaosinjector.service.ChaosConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The proxy hop {@code worker} actually calls instead of {@code mock-downstream} directly
 * (PHASE_7_DESIGN.md Decision 2). Wave 1 scope: this is a pure passthrough. It already reads the
 * live {@link ChaosConfig} so the branch structure matches the design's five-mode table, but only
 * {@code NONE}'s forwarding behavior is implemented — every other mode falls through to the same
 * passthrough for now. Wave 3 replaces each TODO branch with real fault behavior (delay before
 * forwarding, short-circuited 503/400, or a DROP-mode timeout) without changing this method's
 * shape.
 */
@RestController
@RequestMapping("/v1")
public class ProcessController {

  private final ChaosConfigService chaosConfigService;
  private final MockDownstreamClient mockDownstreamClient;

  public ProcessController(
      ChaosConfigService chaosConfigService, MockDownstreamClient mockDownstreamClient) {
    this.chaosConfigService = chaosConfigService;
    this.mockDownstreamClient = mockDownstreamClient;
  }

  @PostMapping("/process")
  public ResponseEntity<ProcessResponse> process(@RequestBody ProcessRequest request) {
    ChaosConfig chaosConfig = chaosConfigService.get();

    switch (chaosConfig.mode()) {
      case NONE -> {
        // Forward unmodified; mock-downstream always succeeds.
      }
      case LATENCY -> {
        // TODO(Wave 3): sleep latencyMs before forwarding, then return mock-downstream's real
        // response. Falls through to passthrough for now.
      }
      case ERROR_5XX -> {
        // TODO(Wave 3): short-circuit with probability probabilityPct, returning 503 without
        // ever calling mock-downstream. Falls through to passthrough for now.
      }
      case ERROR_4XX -> {
        // TODO(Wave 3): short-circuit with probability probabilityPct, returning 400 without
        // ever calling mock-downstream. Falls through to passthrough for now.
      }
      case DROP -> {
        // TODO(Wave 3): sleep past worker's read timeout without ever forwarding or responding.
        // Falls through to passthrough for now.
      }
    }

    ProcessResponse response = mockDownstreamClient.process(request);
    return ResponseEntity.ok(response);
  }
}
