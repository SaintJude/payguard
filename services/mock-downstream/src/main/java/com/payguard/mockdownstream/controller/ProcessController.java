package com.payguard.mockdownstream.controller;

import com.payguard.mockdownstream.dto.ProcessRequest;
import com.payguard.mockdownstream.dto.ProcessResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "real" simulated payment processor. Deliberately boring: no chaos awareness, no failure
 * modes, no persistence — every request always succeeds. Every fault-injection decision lives one
 * hop upstream, in {@code chaos-injector} (see PHASE_7_DESIGN.md Decision 1/2); this service is
 * only ever reached by {@code chaos-injector} forwarding a request through.
 */
@RestController
@RequestMapping("/v1")
public class ProcessController {

  @PostMapping("/process")
  public ResponseEntity<ProcessResponse> process(@RequestBody ProcessRequest request) {
    return ResponseEntity.ok(ProcessResponse.processed(request.paymentId()));
  }
}
