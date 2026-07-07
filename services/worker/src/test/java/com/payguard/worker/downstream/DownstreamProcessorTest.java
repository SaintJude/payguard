package com.payguard.worker.downstream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DownstreamProcessorTest {

  @Test
  void firstCallForAPaymentIdThrowsTransientException() {
    DownstreamProcessor processor = new DownstreamProcessor();
    UUID paymentId = UUID.randomUUID();

    assertThatThrownBy(() -> processor.process(paymentId))
        .isInstanceOf(TransientDownstreamException.class);
  }

  @Test
  void secondCallForTheSamePaymentIdSucceeds() {
    DownstreamProcessor processor = new DownstreamProcessor();
    UUID paymentId = UUID.randomUUID();

    assertThatThrownBy(() -> processor.process(paymentId))
        .isInstanceOf(TransientDownstreamException.class);

    assertThatCode(() -> processor.process(paymentId)).doesNotThrowAnyException();
  }

  @Test
  void eachPaymentIdGetsItsOwnFirstAttemptFailure() {
    DownstreamProcessor processor = new DownstreamProcessor();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    assertThatThrownBy(() -> processor.process(first))
        .isInstanceOf(TransientDownstreamException.class);

    // A different paymentId has never been attempted before, so it also
    // fails on its own first call, independent of the counter above.
    assertThatThrownBy(() -> processor.process(second))
        .isInstanceOf(TransientDownstreamException.class);
  }
}
