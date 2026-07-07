package com.payguard.paymentapi;

import org.junit.jupiter.api.Test;

class PaymentApiApplicationTests {

  @Test
  void contextLoadsPlaceholder() {
    // Full @SpringBootTest is deferred until Testcontainers is wired in
    // (Phase 3+); running Postgres/Redis as prerequisites for the test
    // suite isn't worth it yet for a single-developer learning project.
  }
}
