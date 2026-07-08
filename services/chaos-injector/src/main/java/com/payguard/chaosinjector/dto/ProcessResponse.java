package com.payguard.chaosinjector.dto;

import java.util.UUID;

public record ProcessResponse(UUID paymentId, String status) {}
