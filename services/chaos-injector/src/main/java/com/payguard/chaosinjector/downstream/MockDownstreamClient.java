package com.payguard.chaosinjector.downstream;

import com.payguard.chaosinjector.dto.ProcessRequest;
import com.payguard.chaosinjector.dto.ProcessResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The passthrough hop to {@code mock-downstream}. Used on every branch that isn't short-circuited
 * by an active chaos mode ({@code NONE} always; {@code LATENCY} after its delay; every other mode
 * falls through to this too until Wave 3 implements real short-circuiting — see {@code
 * ProcessController}).
 */
@Component
public class MockDownstreamClient {

  private final RestClient mockDownstreamRestClient;

  public MockDownstreamClient(RestClient mockDownstreamRestClient) {
    this.mockDownstreamRestClient = mockDownstreamRestClient;
  }

  public ProcessResponse process(ProcessRequest request) {
    return mockDownstreamRestClient
        .post()
        .uri("/v1/process")
        .body(request)
        .retrieve()
        .body(ProcessResponse.class);
  }
}
