package com.payguard.paymentapi.controller;

import com.payguard.paymentapi.domain.Payment;
import com.payguard.paymentapi.dto.CreatePaymentRequest;
import com.payguard.paymentapi.dto.PaymentResponse;
import com.payguard.paymentapi.service.PaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @PostMapping
  public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
    Payment payment = paymentService.createPayment(request);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(PaymentResponse.from(payment));
  }

  @GetMapping("/{id}")
  public ResponseEntity<PaymentResponse> get(@PathVariable UUID id) {
    return paymentService
        .findById(id)
        .map(PaymentResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
