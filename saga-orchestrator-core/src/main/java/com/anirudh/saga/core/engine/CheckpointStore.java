package com.anirudh.saga.core.engine;

import com.anirudh.saga.core.audit.SagaExecutionLog;
import com.anirudh.saga.core.domain.SagaInstance;
import com.anirudh.saga.core.repository.SagaExecutionLogRepository;
import com.anirudh.saga.core.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaExecutionLogRepository executionLogRepository;
    private final Clock clock;

    public CheckpointStore(SagaInstanceRepository sagaInstanceRepository,
                           SagaExecutionLogRepository executionLogRepository,
                           Clock clock) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.executionLogRepository = executionLogRepository;
        this.clock = clock;
    }

    public SagaInstance save(SagaInstance instance) {
        instance.touch(Instant.now(clock));
        return sagaInstanceRepository.save(instance);
    }

    public void logEvent(String sagaId, String stepName, String event, String data) {
        SagaExecutionLog entry = SagaExecutionLog.of(sagaId, stepName, event, data, Instant.now(clock));
        executionLogRepository.save(entry);
        log.info("[sagaId={}] Event logged: step={} event={}", sagaId, stepName, event);
    }
}
