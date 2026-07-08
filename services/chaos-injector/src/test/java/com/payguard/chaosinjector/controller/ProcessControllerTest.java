package com.payguard.chaosinjector.controller;

import static org.mockito.ArgumentMatchers.any;
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
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProcessController.class)
class ProcessControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ChaosConfigService chaosConfigService;

  @MockitoBean private MockDownstreamClient mockDownstreamClient;

  private String requestBody(UUID paymentId) {
    return "{\"paymentId\": \"%s\", \"amount\": 42.00}".formatted(paymentId);
  }

  @Test
  void noneModeForwardsToMockDownstreamAndReturnsItsResponse() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.NONE, 0, 0));
    when(mockDownstreamClient.process(any(ProcessRequest.class)))
        .thenReturn(new ProcessResponse(paymentId, "PROCESSED"));

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

  /**
   * Wave 1 scope: chaos-injector is a passthrough proxy — every mode other than NONE is scaffolded
   * (the config API accepts it) but its real fault behavior isn't implemented until Wave 3, so
   * every mode currently falls through to the same passthrough forwarding.
   */
  @ParameterizedTest
  @EnumSource(
      value = ChaosMode.class,
      names = {"LATENCY", "ERROR_5XX", "ERROR_4XX", "DROP"})
  void everyNonNoneModeCurrentlyFallsThroughToPassthrough(ChaosMode mode) throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(mode, 100, 50));
    when(mockDownstreamClient.process(any(ProcessRequest.class)))
        .thenReturn(new ProcessResponse(paymentId, "PROCESSED"));

    mockMvc
        .perform(
            post("/v1/process")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(paymentId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"));

    verify(mockDownstreamClient).process(any(ProcessRequest.class));
  }
}
