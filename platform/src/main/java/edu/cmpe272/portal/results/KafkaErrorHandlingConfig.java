package edu.cmpe272.portal.results;

import edu.cmpe272.portal.messaging.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    static final long INITIAL_BACKOFF_MS = 500;
    static final double MULTIPLIER = 2.0;
    static final int MAX_RETRIES = 3;

    /**
     * Retries a failed record 3 times (0.5 s, 1 s, 2 s), then publishes it unchanged to
     * {@code <topic>.dlq} on the same partition. The offset is committed only after the DLQ publish
     * succeeds. Records are redacted results, so the DLQ never holds unredacted text; stack traces
     * are left out of the headers.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + Topics.DLQ_SUFFIX, record.partition()));
        recoverer.excludeHeader(DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        recoverer.setFailIfSendResultIsError(true);

        var backOff = new ExponentialBackOff(INITIAL_BACKOFF_MS, MULTIPLIER);
        backOff.setMaxAttempts(MAX_RETRIES);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidResultException.class, DeserializationException.class);
        handler.setCommitRecovered(true);
        return handler;
    }
}
