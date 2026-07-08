package com.payguard.chaosinjector.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ProcessRequest(UUID paymentId, BigDecimal amount) {}
