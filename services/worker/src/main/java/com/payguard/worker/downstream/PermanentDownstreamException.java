package com.payguard.worker.downstream;

/**
 * A downstream failure known not to be worth retrying — {@code chaos-injector} returning a {@code
 * 400} (its {@code ERROR_4XX} mode, Wave 3). Unlike {@link TransientDownstreamException}, this is
 * never passed to {@code @Retryable}: {@link com.payguard.worker.service.PaymentProcessingService}
 * catches it directly and marks the payment {@code FAILED} immediately, resolving
 * PHASE_1_DESIGN.md's deferred Open Question about a genuine permanent-failure path.
 */
public class PermanentDownstreamException extends RuntimeException {

  public PermanentDownstreamException(String message) {
    super(message);
  }

  public PermanentDownstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
