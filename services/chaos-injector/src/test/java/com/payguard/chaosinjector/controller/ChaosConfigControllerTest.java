package com.payguard.chaosinjector.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payguard.chaosinjector.domain.ChaosConfig;
import com.payguard.chaosinjector.domain.ChaosMode;
import com.payguard.chaosinjector.service.ChaosConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChaosConfigController.class)
class ChaosConfigControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ChaosConfigService chaosConfigService;

  @Test
  void getReturnsCurrentConfig() throws Exception {
    when(chaosConfigService.get()).thenReturn(new ChaosConfig(ChaosMode.NONE, 0, 0));

    mockMvc
        .perform(get("/chaos/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("NONE"))
        .andExpect(jsonPath("$.latencyMs").value(0))
        .andExpect(jsonPath("$.probabilityPct").value(0));
  }

  @Test
  void putWithValidModeUpdatesAndEchoesNewState() throws Exception {
    ChaosConfig requested = new ChaosConfig(ChaosMode.LATENCY, 500, 0);
    when(chaosConfigService.update(requested)).thenReturn(requested);

    mockMvc
        .perform(
            put("/chaos/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"LATENCY\", \"latencyMs\": 500, \"probabilityPct\": 0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("LATENCY"))
        .andExpect(jsonPath("$.latencyMs").value(500));
  }

  @Test
  void putWithOmittedNumericParamsDefaultsToZero() throws Exception {
    when(chaosConfigService.update(new ChaosConfig(ChaosMode.ERROR_5XX, 0, 0)))
        .thenReturn(new ChaosConfig(ChaosMode.ERROR_5XX, 0, 0));

    mockMvc
        .perform(
            put("/chaos/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"ERROR_5XX\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("ERROR_5XX"))
        .andExpect(jsonPath("$.latencyMs").value(0))
        .andExpect(jsonPath("$.probabilityPct").value(0));
  }

  @Test
  void putWithUnrecognizedModeReturns400() throws Exception {
    mockMvc
        .perform(
            put("/chaos/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"NOT_A_REAL_MODE\"}"))
        .andExpect(status().isBadRequest());

    org.mockito.Mockito.verify(chaosConfigService, org.mockito.Mockito.never()).update(any());
  }

  @Test
  void putWithMissingModeReturns400() throws Exception {
    mockMvc
        .perform(put("/chaos/config").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }
}
