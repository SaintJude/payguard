package com.payguard.mockdownstream.dto;

import java.util.UUID;

public record ProcessResponse(UUID paymentId, String status) {

  public static ProcessResponse processed(UUID paymentId) {
    return new ProcessResponse(paymentId, "PROCESSED");
  }
}
