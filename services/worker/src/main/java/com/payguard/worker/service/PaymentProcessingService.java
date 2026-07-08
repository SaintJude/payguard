package com.payguard.worker.service;

import com.payguard.worker.domain.Payment;
import com.payguard.worker.domain.PaymentStatus;
import com.payguard.worker.downstream.DownstreamProcessor;
import com.payguard.worker.downstream.PermanentDownstreamException;
import com.payguard.worker.downstream.TransientDownstreamException;
import com.payguard.worker.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
public class PaymentProcessingService {

  private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);
  private static final String PAYMENTS_PROCESSED_METRIC = "payments_processed_total";

  private final PaymentRepository paymentRepository;
  private final DownstreamProcessor downstreamProcessor;
  private final MeterRegistry meterRegistry;

  public PaymentProcessingService(
      PaymentRepository paymentRepository,
      DownstreamProcessor downstreamProcessor,
      MeterRegistry meterRegistry) {
    this.paymentRepository = paymentRepository;
    this.downstreamProcessor = downstreamProcessor;
    this.meterRegistry = meterRegistry;
  }

  @Retryable(
      retryFor = TransientDownstreamException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 200, multiplier = 2))
  public void processPayment(UUID paymentId) {
    Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
    if (maybePayment.isEmpty()) {
      log.error("Payment {} not found; skipping status update", paymentId);
      return;
    }
    Payment payment = maybePayment.get();

    try {
      downstreamProcessor.process(paymentId, payment.getAmount());
    } catch (PermanentDownstreamException ex) {
      // Not retryable -- straight to FAILED, same terminal handling @Recover gives
      // TransientDownstreamException once its own retries are exhausted.
      log.warn("Permanent downstream failure for payment {}; not retrying", paymentId, ex);
      markTerminal(payment, PaymentStatus.FAILED);
      return;
    }

    markTerminal(payment, PaymentStatus.COMPLETED);
  }

  @Recover
  public void recover(TransientDownstreamException ex, UUID paymentId) {
    Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
    if (maybePayment.isEmpty()) {
      log.error("Payment {} not found; skipping status update", paymentId);
      return;
    }

    markTerminal(maybePayment.get(), PaymentStatus.FAILED);
  }

  private void markTerminal(Payment payment, PaymentStatus status) {
    payment.setStatus(status);
    payment.setUpdatedAt(Instant.now());
    paymentRepository.save(payment);
    meterRegistry.counter(PAYMENTS_PROCESSED_METRIC, "status", status.name()).increment();
  }
}
