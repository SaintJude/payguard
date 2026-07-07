package com.payguard.paymentapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.paymentapi.domain.Payment;
import com.payguard.paymentapi.domain.PaymentStatus;
import com.payguard.paymentapi.dto.CreatePaymentRequest;
import com.payguard.paymentapi.dto.PaymentResponse;
import com.payguard.paymentapi.service.PaymentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private PaymentService paymentService;

  private Payment samplePayment(String idempotencyKey) {
    Instant now = Instant.now();
    return new Payment(
        UUID.randomUUID(),
        new BigDecimal("25.00"),
        PaymentStatus.PENDING,
        idempotencyKey,
        now,
        now);
  }

  @Test
  void createWithValidBodyReturns202WithPaymentBody() throws Exception {
    Payment payment = samplePayment("idem-1");
    CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("25.00"), "idem-1");
    when(paymentService.createPayment(request)).thenReturn(payment);

    mockMvc
        .perform(
            post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(content().json(objectMapper.writeValueAsString(PaymentResponse.from(payment))));
  }

  @Test
  void createWithAmountBelowMinimumReturns400WithFieldErrorMap() throws Exception {
    String body =
        """
                {"amount": 0.00, "idempotencyKey": "idem-1"}
                """;

    mockMvc
        .perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.amount").exists())
        .andExpect(jsonPath("$.idempotencyKey").doesNotExist());

    verify(paymentService, never()).createPayment(any());
  }

  @Test
  void createWithBlankIdempotencyKeyReturns400WithFieldErrorMap() throws Exception {
    String body =
        """
                {"amount": 25.00, "idempotencyKey": ""}
                """;

    mockMvc
        .perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.idempotencyKey").exists())
        .andExpect(jsonPath("$.amount").doesNotExist());

    verify(paymentService, never()).createPayment(any());
  }

  @Test
  void getWhenFoundReturns200WithPaymentBody() throws Exception {
    Payment payment = samplePayment("idem-2");
    when(paymentService.findById(payment.getId())).thenReturn(Optional.of(payment));

    mockMvc
        .perform(get("/payments/{id}", payment.getId()))
        .andExpect(status().isOk())
        .andExpect(content().json(objectMapper.writeValueAsString(PaymentResponse.from(payment))));
  }

  @Test
  void getWhenNotFoundReturns404() throws Exception {
    UUID id = UUID.randomUUID();
    when(paymentService.findById(id)).thenReturn(Optional.empty());

    mockMvc.perform(get("/payments/{id}", id)).andExpect(status().isNotFound());
  }
}
