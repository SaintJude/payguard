package com.payguard.worker.service;

import com.payguard.worker.domain.Payment;
import com.payguard.worker.domain.PaymentStatus;
import com.payguard.worker.downstream.DownstreamProcessor;
import com.payguard.worker.downstream.TransientDownstreamException;
import com.payguard.worker.repository.PaymentRepository;
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

  private final PaymentRepository paymentRepository;
  private final DownstreamProcessor downstreamProcessor;

  public PaymentProcessingService(
      PaymentRepository paymentRepository, DownstreamProcessor downstreamProcessor) {
    this.paymentRepository = paymentRepository;
    this.downstreamProcessor = downstreamProcessor;
  }

  @Retryable(
      retryFor = TransientDownstreamException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 200, multiplier = 2))
  public void processPayment(UUID paymentId) {
    downstreamProcessor.process(paymentId);

    Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
    if (maybePayment.isEmpty()) {
      log.error("Payment {} not found; skipping status update", paymentId);
      return;
    }

    Payment payment = maybePayment.get();
    payment.setStatus(PaymentStatus.COMPLETED);
    payment.setUpdatedAt(Instant.now());
    paymentRepository.save(payment);
  }

  @Recover
  public void recover(TransientDownstreamException ex, UUID paymentId) {
    Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
    if (maybePayment.isEmpty()) {
      log.error("Payment {} not found; skipping status update", paymentId);
      return;
    }

    Payment payment = maybePayment.get();
    payment.setStatus(PaymentStatus.FAILED);
    payment.setUpdatedAt(Instant.now());
    paymentRepository.save(payment);
  }
}
