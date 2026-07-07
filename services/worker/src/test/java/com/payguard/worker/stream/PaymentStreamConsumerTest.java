package com.payguard.worker.stream;

import static com.payguard.worker.stream.PaymentStreamConsumer.isBusyGroupError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.payguard.worker.service.PaymentProcessingService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentStreamConsumerTest {

  @Mock private StringRedisTemplate redisTemplate;

  @Mock private PaymentProcessingService paymentProcessingService;

  @Mock private StreamOperations<String, Object, Object> streamOperations;

  @Mock private ApplicationArguments applicationArguments;

  /**
   * Mirrors the real shape thrown by Spring Data Redis: a generic {@link DataAccessException}
   * ("Error in execution") wrapping the actual {@code io.lettuce.core.RedisBusyException} as its
   * cause, whose message carries the "BUSYGROUP" text.
   */
  @Test
  void detectsBusyGroupInNestedCause() {
    RuntimeException redisBusyException =
        new RuntimeException("BUSYGROUP Consumer Group name already exists");
    DataAccessException wrapped =
        new InvalidDataAccessResourceUsageException("Error in execution", redisBusyException);

    assertThat(isBusyGroupError(wrapped)).isTrue();
  }

  @Test
  void doesNotFlagUnrelatedExceptionAsBusyGroup() {
    RuntimeException otherCause = new RuntimeException("Connection closed prematurely");
    DataAccessException wrapped =
        new InvalidDataAccessResourceUsageException("Error in execution", otherCause);

    assertThat(isBusyGroupError(wrapped)).isFalse();
  }

  @Test
  void detectsBusyGroupWhenMessageIsOnTopLevelException() {
    DataAccessException noCause =
        new InvalidDataAccessResourceUsageException("BUSYGROUP Consumer Group name already exists");

    assertThat(isBusyGroupError(noCause)).isTrue();
  }

  /**
   * Regression test for the bug documented in PHASE_1_REPORT.md: the poll thread started by {@code
   * run()} was marked daemon, so once {@code ApplicationRunner.run()} returned there were zero
   * non-daemon threads left and the JVM exited within ~200ms of startup, killing the poll thread
   * before it ever consumed anything. The thread must be non-daemon so it keeps the JVM alive for
   * its intended lifetime (running until explicitly killed), not until Java decides nothing else is
   * keeping it alive.
   */
  @Test
  void runStartsConsumerThreadThatIsNotDaemon() throws InterruptedException {
    when(redisTemplate.opsForStream()).thenReturn(streamOperations);

    // Force the poll loop into its error-retry branch (log + 1s sleep) on
    // its very first iteration. This lets the test deterministically know
    // the thread has actually started polling (via the latch) before
    // asserting on it, and gives us a sleep to interrupt so the thread
    // terminates promptly afterward -- important now that it's
    // non-daemon, a poll loop left spinning forever would hang the build.
    CountDownLatch pollInvoked = new CountDownLatch(1);
    when(streamOperations.read(
            any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
        .thenAnswer(
            invocation -> {
              pollInvoked.countDown();
              throw new RuntimeException("boom");
            });

    PaymentStreamConsumer consumer =
        new PaymentStreamConsumer(
            redisTemplate,
            paymentProcessingService,
            "payments-stream",
            "worker-group",
            "consumer-1");

    consumer.run(applicationArguments);

    Thread consumerThread = findThreadByName("payment-stream-consumer");
    assertThat(consumerThread).as("consumer thread should have been started").isNotNull();
    try {
      assertThat(pollInvoked.await(2, TimeUnit.SECONDS))
          .as("poll loop should have started")
          .isTrue();
      assertThat(consumerThread.isDaemon())
          .as(
              "consumer thread must be non-daemon so the JVM stays alive for the poll loop's lifetime")
          .isFalse();
    } finally {
      consumerThread.interrupt();
      consumerThread.join(TimeUnit.SECONDS.toMillis(2));
    }
  }

  private static Thread findThreadByName(String name) {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (name.equals(thread.getName())) {
        return thread;
      }
    }
    return null;
  }
}
