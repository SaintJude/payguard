package com.payguard.paymentapi.service;

import com.payguard.paymentapi.domain.Payment;
import com.payguard.paymentapi.domain.PaymentStatus;
import com.payguard.paymentapi.dto.CreatePaymentRequest;
import com.payguard.paymentapi.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final StringRedisTemplate redisTemplate;
    private final String streamKey;

    public PaymentService(PaymentRepository paymentRepository,
                           StringRedisTemplate redisTemplate,
                           @Value("${payguard.stream.key}") String streamKey) {
        this.paymentRepository = paymentRepository;
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
    }

    /**
     * Note: the DB write and the stream publish below are not atomic (the
     * classic "dual write" problem). If the process crashes between save()
     * and the stream add, the payment is stuck in PENDING forever. Fine for
     * a learning project; a production system would use an outbox pattern.
     */
    @Transactional
    public Payment createPayment(CreatePaymentRequest request) {
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = Instant.now();
        Payment payment = new Payment(
                UUID.randomUUID(),
                request.amount(),
                PaymentStatus.PENDING,
                request.idempotencyKey(),
                now,
                now
        );
        paymentRepository.save(payment);
        enqueueForProcessing(payment.getId());
        return payment;
    }

    public Optional<Payment> findById(UUID id) {
        return paymentRepository.findById(id);
    }

    private void enqueueForProcessing(UUID paymentId) {
        redisTemplate.opsForStream().add(streamKey, Map.of("paymentId", paymentId.toString()));
    }
}
