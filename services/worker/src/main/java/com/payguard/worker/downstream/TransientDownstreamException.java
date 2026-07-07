package com.payguard.worker.downstream;

public class TransientDownstreamException extends RuntimeException {

  public TransientDownstreamException(String message) {
    super(message);
  }
}
