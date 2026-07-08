package com.payguard.chaosinjector.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payguard.chaosinjector.domain.ChaosConfig;
import com.payguard.chaosinjector.domain.ChaosMode;
import com.payguard.chaosinjector.downstream.MockDownstreamClient;
import com.payguard.chaosinjector.dto.ProcessRequest;
import com.payguard.chaosinjector.dto.ProcessResponse;
import com.payguard.chaosinjector.service.ChaosConfigService;
import com.payguard.chaosinjector.util.ChaosRandom;
import com.payguard.chaosinjector.util.Sleeper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wave 3: real fault behavior for all five modes. {@link Sleeper} and {@link ChaosRandom} are
 * mocked so LATENCY/DROP tests don't actually wait out a real delay and ERROR_5XX/ERROR_4XX
 * probability branches are deterministic rather than flaky.
 */
@WebMvcTest(ProcessController.class)
class ProcessControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ChaosConfigService chaosConfigService;

  @MockitoBean private MockDownstreamClient mockDownstreamClient;

  @MockitoBean private ChaosRandom chaosRandom;

  @MockitoBean private Sleeper sleeper;

  private String requestBody(UUID paymentId) {
    return "{\"paymentId\": \"%s\", \"amount\": 42.00}".formatted(paymentId);
  }

  private void stubMockDownstream(UUID paymentId) {
    when(mockDownstreamClient.process(any(ProcessRequest.class)))
        .thenReturn(new ProcessResponse(paymentId, "PROCESSED"));
  }

  @Test
  void noneModeForwardsToMockDownstreamAndReturnsItsResponse() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.NONE, 0, 0));
    stubMockDownstream(paymentId);

    mockMvc
        .perform(
            post("/v1/process")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(paymentId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$.status").value("PROCESSED"));

    verify(mockDownstreamClient).process(new ProcessRequest(paymentId, new BigDecimal("42.00")));
  }

  @Test
  void latencyModeSleepsBeforeForwardingThenReturnsRealResponse() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.LATENCY, 500, 0));
    stubMockDownstream(paymentId);

    mockMvc
        .perform(
            post("/v1/process")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(paymentId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"));

    verify(sleeper).sleep(500L);
    verify(mockDownstreamClient).process(any(ProcessRequest.class));
  }

  @Test
  void error5xxShortCircuitsWithoutCallingMockDownstreamWhenProbabilityHits() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.ERROR_5XX, 0, 100));
    when(chaosRandom.nextPercent()).thenReturn(0); // 0 < 100 -> short-circuit

    mockMvc
        .perform(
            post("/v1/process")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(paymentId)))
        .andExpect(status().isServiceUnavailable());

    verify(mockDownstreamClient, never()).process(any(ProcessRequest.class));
  }

  @Test
  void error5xxPassesThroughWhenProbabilityMisses() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.ERROR_5XX, 0, 0));
    when(chaosRandom.nextPercent()).thenReturn(0); // 0 < 0 is false -> passthrough
    stubMockDownstream(paymentId);

    mockMvc
        .perform(
            post("/v1/process")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(paymentId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"));

    verify(mockDownstreamClient).process(any(ProcessRequest.class));
  }

  @Test
  void error4xxShortCircuitsWithoutCallingMockDownstreamWhenProbabilityHits() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.ERROR_4XX, 0, 100));
    when(chaosRandom.nextPercent()).thenReturn(99); // 99 < 100 -> short-circuit

    mockMvc
        .perform(
            post("/v1/process")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(paymentId)))
        .andExpect(status().isBadRequest());

    verify(mockDownstreamClient, never()).process(any(ProcessRequest.class));
  }

  @Test
  void error4xxPassesThroughWhenProbabilityMisses() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.ERROR_4XX, 0, 50));
    when(chaosRandom.nextPercent()).thenReturn(50); // 50 < 50 is false -> passthrough
    stubMockDownstream(paymentId);

    mockMvc
        .perform(
            post("/v1/process")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(paymentId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"));

    verify(mockDownstreamClient).process(any(ProcessRequest.class));
  }

  @Test
  void dropModeSleepsPastWorkerTimeoutAndNeverCallsMockDownstream() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.DROP, 0, 0));

    mockMvc.perform(
        post("/v1/process")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody(paymentId)));

    verify(sleeper).sleep(eq(5_000L));
    verify(mockDownstreamClient, never()).process(any(ProcessRequest.class));
  }
}
