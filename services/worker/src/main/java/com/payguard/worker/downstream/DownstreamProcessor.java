package com.payguard.worker.downstream;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stubbed downstream payment processor. Deliberately fails on the very first
 * call for a given paymentId and succeeds on every call after that, to
 * simulate a transient downstream failure and prove the retry path works.
 * Attempt counts are in-memory only and reset on worker restart.
 */
@Component
public class DownstreamProcessor {

    private final ConcurrentHashMap<UUID, AtomicInteger> attemptCounts = new ConcurrentHashMap<>();

    public void process(UUID paymentId) {
        AtomicInteger attempts = attemptCounts.computeIfAbsent(paymentId, id -> new AtomicInteger(0));
        int attemptNumber = attempts.incrementAndGet();
        if (attemptNumber == 1) {
            throw new TransientDownstreamException(
                    "Simulated transient downstream failure for payment " + paymentId);
        }
    }
}
