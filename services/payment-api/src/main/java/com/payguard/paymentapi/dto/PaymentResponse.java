package com.payguard.paymentapi.dto;

import com.payguard.paymentapi.domain.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    BigDecimal amount,
    String status,
    String idempotencyKey,
    Instant createdAt,
    Instant updatedAt) {
  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(
        payment.getId(),
        payment.getAmount(),
        payment.getStatus().name(),
        payment.getIdempotencyKey(),
        payment.getCreatedAt(),
        payment.getUpdatedAt());
  }
}
