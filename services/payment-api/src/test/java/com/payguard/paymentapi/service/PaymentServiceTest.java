package com.payguard.paymentapi.service;

import com.payguard.paymentapi.domain.Payment;
import com.payguard.paymentapi.domain.PaymentStatus;
import com.payguard.paymentapi.dto.CreatePaymentRequest;
import com.payguard.paymentapi.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String STREAM_KEY = "payments-stream";

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private PaymentService service;

    private Payment existingPayment(String idempotencyKey) {
        Instant now = Instant.now();
        return new Payment(UUID.randomUUID(), BigDecimal.TEN, PaymentStatus.COMPLETED, idempotencyKey, now, now);
    }

    @Test
    void createPaymentWithNewIdempotencyKeySavesPendingPaymentAndPublishesToStream() {
        service = new PaymentService(paymentRepository, redisTemplate, STREAM_KEY);
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("25.00"), "idem-new");

        when(paymentRepository.findByIdempotencyKey("idem-new")).thenReturn(Optional.empty());
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        Payment result = service.createPayment(request);

        ArgumentCaptor<Payment> savedCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(savedCaptor.capture());
        Payment saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getAmount()).isEqualByComparingTo("25.00");
        assertThat(saved.getIdempotencyKey()).isEqualTo("idem-new");
        assertThat(saved.getId()).isNotNull();

        assertThat(result).isSameAs(saved);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> streamBodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOperations).add(eq(STREAM_KEY), streamBodyCaptor.capture());
        assertThat(streamBodyCaptor.getValue()).containsEntry("paymentId", saved.getId().toString());
    }

    @Test
    void createPaymentWithExistingIdempotencyKeyReturnsExistingPaymentWithoutSavingOrPublishing() {
        service = new PaymentService(paymentRepository, redisTemplate, STREAM_KEY);
        Payment existing = existingPayment("idem-dup");
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("25.00"), "idem-dup");

        when(paymentRepository.findByIdempotencyKey("idem-dup")).thenReturn(Optional.of(existing));

        Payment result = service.createPayment(request);

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result).isSameAs(existing);
        verify(paymentRepository, never()).save(any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void findByIdReturnsPaymentWhenFound() {
        service = new PaymentService(paymentRepository, redisTemplate, STREAM_KEY);
        Payment payment = existingPayment("idem-found");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        Optional<Payment> result = service.findById(payment.getId());

        assertThat(result).contains(payment);
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        service = new PaymentService(paymentRepository, redisTemplate, STREAM_KEY);
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        Optional<Payment> result = service.findById(id);

        assertThat(result).isEmpty();
    }
}
