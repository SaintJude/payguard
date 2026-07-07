package com.payguard.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payguard.worker.domain.Payment;
import com.payguard.worker.domain.PaymentStatus;
import com.payguard.worker.downstream.DownstreamProcessor;
import com.payguard.worker.downstream.TransientDownstreamException;
import com.payguard.worker.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentProcessingServiceTest {

  @Mock private PaymentRepository paymentRepository;

  @Mock private DownstreamProcessor downstreamProcessor;

  private PaymentProcessingService service;

  private Payment newPendingPayment(UUID id) {
    Instant now = Instant.now();
    return new Payment(id, BigDecimal.TEN, PaymentStatus.PENDING, "idem-" + id, now, now);
  }

  @Test
  void successfulProcessingSetsStatusCompletedAndSaves() {
    service = new PaymentProcessingService(paymentRepository, downstreamProcessor);
    UUID paymentId = UUID.randomUUID();
    Payment payment = newPendingPayment(paymentId);

    doNothing().when(downstreamProcessor).process(paymentId);
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

    service.processPayment(paymentId);

    ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(captor.getValue().getUpdatedAt()).isNotNull();
  }

  @Test
  void successfulProcessingWhenPaymentMissingLogsAndDoesNotSave() {
    service = new PaymentProcessingService(paymentRepository, downstreamProcessor);
    UUID paymentId = UUID.randomUUID();

    doNothing().when(downstreamProcessor).process(paymentId);
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

    service.processPayment(paymentId);

    verify(paymentRepository, never()).save(any());
  }

  @Test
  void recoverSetsStatusFailedAndSaves() {
    service = new PaymentProcessingService(paymentRepository, downstreamProcessor);
    UUID paymentId = UUID.randomUUID();
    Payment payment = newPendingPayment(paymentId);

    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

    service.recover(new TransientDownstreamException("exhausted"), paymentId);

    ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(captor.getValue().getUpdatedAt()).isNotNull();
  }

  @Test
  void recoverWhenPaymentMissingLogsAndDoesNotSave() {
    service = new PaymentProcessingService(paymentRepository, downstreamProcessor);
    UUID paymentId = UUID.randomUUID();

    when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

    service.recover(new TransientDownstreamException("exhausted"), paymentId);

    verify(paymentRepository, never()).save(any());
  }
}
