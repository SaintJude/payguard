package com.payguard.worker.stream;

import com.payguard.worker.service.PaymentProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Polls {@code payments-stream} via a Redis Streams consumer group and hands
 * each entry to {@link PaymentProcessingService}. Runs its blocking read loop
 * on a background thread so it never blocks Spring Boot startup.
 */
@Component
public class PaymentStreamConsumer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PaymentStreamConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final PaymentProcessingService paymentProcessingService;
    private final String streamKey;
    private final String group;
    private final String consumerName;

    public PaymentStreamConsumer(StringRedisTemplate redisTemplate,
                                  PaymentProcessingService paymentProcessingService,
                                  @Value("${payguard.stream.key}") String streamKey,
                                  @Value("${payguard.stream.group}") String group,
                                  @Value("${payguard.stream.consumer}") String consumerName) {
        this.redisTemplate = redisTemplate;
        this.paymentProcessingService = paymentProcessingService;
        this.streamKey = streamKey;
        this.group = group;
        this.consumerName = consumerName;
    }

    @Override
    public void run(ApplicationArguments args) {
        createConsumerGroup();

        Thread consumerThread = new Thread(this::pollLoop, "payment-stream-consumer");
        consumerThread.start();
    }

    private void createConsumerGroup() {
        try {
            redisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(streamKey.getBytes(StandardCharsets.UTF_8), group, ReadOffset.from("0"), true));
        } catch (DataAccessException ex) {
            if (isBusyGroupError(ex)) {
                log.info("Consumer group {} already exists on stream {}; continuing", group, streamKey);
            } else {
                throw ex;
            }
        }
    }

    /**
     * Redis Streams returns a {@code BUSYGROUP} error when the consumer group
     * already exists. Spring Data Redis wraps this as a {@link DataAccessException}
     * (typically {@code RedisSystemException}) whose own message is a generic
     * "Error in execution" — the actual {@code BUSYGROUP ...} text only appears on
     * the nested cause (e.g. {@code io.lettuce.core.RedisBusyException}). So the
     * cause chain must be walked rather than checking the top-level message.
     */
    static boolean isBusyGroupError(Throwable ex) {
        Throwable mostSpecificCause = NestedExceptionUtils.getMostSpecificCause(ex);
        return mostSpecificCause.getMessage() != null && mostSpecificCause.getMessage().contains("BUSYGROUP");
    }

    private void pollLoop() {
        Consumer consumer = Consumer.from(group, consumerName);
        StreamReadOptions options = StreamReadOptions.empty().count(1).block(Duration.ofMillis(5000));

        while (true) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(consumer, options, StreamOffset.create(streamKey, ReadOffset.lastConsumed()));

                if (records == null || records.isEmpty()) {
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    handleRecord(record);
                }
            } catch (Exception ex) {
                log.error("Unexpected error reading from stream {}; will retry on next poll", streamKey, ex);
                try {
                    Thread.sleep(Duration.ofSeconds(1));
                } catch (InterruptedException interruptedEx) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handleRecord(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> fields = record.getValue();
            UUID paymentId = UUID.fromString(String.valueOf(fields.get("paymentId")));

            paymentProcessingService.processPayment(paymentId);

            redisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
        } catch (Exception ex) {
            log.error("Unexpected exception processing record {}; leaving unacked in PEL", record.getId(), ex);
        }
    }
}
