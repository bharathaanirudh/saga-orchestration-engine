package com.anirudh.saga.core.outbox;

import com.anirudh.saga.core.repository.SagaOutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final long FALLBACK_THRESHOLD_MINUTES = 5;

    private final SagaOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    public OutboxPoller(SagaOutboxRepository outboxRepository,
                        KafkaTemplate<String, String> kafkaTemplate,
                        Clock clock) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${saga.outbox.poll-interval-ms:30000}")
    public void poll() {
        Instant threshold = Instant.now(clock).minusSeconds(FALLBACK_THRESHOLD_MINUTES * 60);
        List<OutboxEntry> pending = outboxRepository.findPendingOlderThan(threshold);

        if (pending.isEmpty()) return;

        log.warn("OutboxPoller found {} PENDING entries older than {}min — publishing via fallback",
                pending.size(), FALLBACK_THRESHOLD_MINUTES);

        for (OutboxEntry entry : pending) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        entry.getTopic(), entry.getMessageKey(), entry.getPayload());
                // Attach saga headers — ReplyCorrelator reads these
                record.headers().add(new RecordHeader("X-Saga-Id",
                        entry.getSagaId().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("X-Saga-Step-Id",
                        entry.getStepId().getBytes(StandardCharsets.UTF_8)));

                kafkaTemplate.send(record);
                entry.setStatus(OutboxStatus.PUBLISHED);
                outboxRepository.save(entry);
                log.info("[sagaId={}] Fallback published outbox entry stepId={}",
                        entry.getSagaId(), entry.getStepId());
            } catch (Exception e) {
                log.error("[sagaId={}] Fallback publish failed stepId={}: {}",
                        entry.getSagaId(), entry.getStepId(), e.getMessage());
            }
        }
    }
}
