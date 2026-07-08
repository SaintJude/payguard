package com.payguard.mockdownstream.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProcessController.class)
class ProcessControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void processAlwaysReturns200Processed() throws Exception {
    UUID paymentId = UUID.randomUUID();
    String body =
        """
        {"paymentId": "%s", "amount": 25.00}
        """
            .formatted(paymentId);

    mockMvc
        .perform(post("/v1/process").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$.status").value("PROCESSED"));
  }

  @Test
  void processIsStatelessAndAlwaysSucceedsOnRepeatedCalls() throws Exception {
    UUID paymentId = UUID.randomUUID();
    String body =
        """
        {"paymentId": "%s", "amount": 99.99}
        """
            .formatted(paymentId);

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(post("/v1/process").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("PROCESSED"));
    }
  }
}
