package edu.cmpe272.portal.results;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cmpe272.portal.fhir.FhirWriteBack;
import edu.cmpe272.portal.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Persists AI results from results.urgency / routing / summarization / search. Runs with
 * concurrency 3 (one consumer per partition) and record-level acks; failures are retried with
 * exponential backoff and then dead-lettered by {@link KafkaErrorHandlingConfig}.
 */
@Component
public class ResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResultConsumer.class);

    private final ResultStore store;
    private final ObjectMapper json;
    private final FhirWriteBack fhirWriteBack;

    public ResultConsumer(ResultStore store, ObjectMapper json, FhirWriteBack fhirWriteBack) {
        this.store = store;
        this.json = json;
        this.fhirWriteBack = fhirWriteBack;
    }

    @KafkaListener(id = "results", topics = {"results.urgency", "results.routing", "results.summarization", "results.search"})
    public void onResult(ConsumerRecord<String, String> record) {
        String service = Topics.RESULT_TOPICS.get(record.topic());
        if (service == null) {
            throw new InvalidResultException("unexpected topic");
        }
        ResultEnvelope envelope = ResultEnvelope.parse(service, record.value(), json);
        boolean stored = store.persist(envelope);
        if (!stored) {
            log.debug("duplicate {} result for {} ignored", service, envelope.messageId());
        }
        // Even for a duplicate: the first delivery may have been stored just before a crash. The
        // write-back checks readiness itself and is idempotent.
        if ("routing".equals(service) || "urgency".equals(service)) {
            fhirWriteBack.requestWriteBack(envelope.messageId());
        }
    }
}
