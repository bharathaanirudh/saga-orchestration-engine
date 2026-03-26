package com.anirudh.saga.core.engine;

import com.anirudh.saga.core.domain.*;
import com.anirudh.saga.core.executor.StepExecutorRegistry;
import com.anirudh.saga.core.executor.StepResult;
import com.anirudh.saga.core.exception.SagaNotFoundException;
import com.anirudh.saga.core.loader.SagaDefinitionLoader;
import com.anirudh.saga.core.lock.SagaLockManager;
import com.anirudh.saga.core.repository.SagaInstanceRepository;
import com.anirudh.saga.sdk.contract.SagaStartRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final SagaDefinitionLoader definitionLoader;
    private final SagaInstanceRepository repository;
    private final SagaStateMachine stateMachine;
    private final IdempotencyGuard idempotencyGuard;
    private final StepExecutorRegistry executorRegistry;
    private final CheckpointStore checkpointStore;
    private final SagaLockManager lockManager;
    private final Clock clock;

    public SagaOrchestrator(SagaDefinitionLoader definitionLoader,
                             SagaInstanceRepository repository,
                             SagaStateMachine stateMachine,
                             IdempotencyGuard idempotencyGuard,
                             StepExecutorRegistry executorRegistry,
                             CheckpointStore checkpointStore,
                             SagaLockManager lockManager,
                             Clock clock) {
        this.definitionLoader = definitionLoader;
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.idempotencyGuard = idempotencyGuard;
        this.executorRegistry = executorRegistry;
        this.checkpointStore = checkpointStore;
        this.lockManager = lockManager;
        this.clock = clock;
    }

    public SagaInstance start(SagaStartRequest request) {
        SagaDefinition definition = definitionLoader.getDefinition(request.sagaType());

        String idempotencyKey = request.idempotencyKey() != null
                ? request.idempotencyKey()
                : idempotencyGuard.computeKey(request.sagaType(), request.payload());

        Optional<SagaInstance> existing = idempotencyGuard.findExisting(idempotencyKey);
        if (existing.isPresent()) {
            log.info("[sagaId={}] Idempotency hit — returning existing saga", existing.get().getSagaId());
            return existing.get();
        }

        SagaInstance instance = SagaInstance.create(
                request.sagaType(), request.payload(), idempotencyKey,
                definition.timeoutMinutes(), Instant.now(clock));

        instance = idempotencyGuard.saveOrGetExisting(instance);

        if (instance.getStatus() != SagaStatus.STARTED || instance.getCurrentStep() > 0) {
            log.info("[sagaId={}] Concurrent creation detected — returning winner's saga", instance.getSagaId());
            return instance;
        }

        // Acquire aggregate lock if defined in saga YAML
        acquireLockIfNeeded(instance, definition);

        instance = stateMachine.initialize(instance);
        log.info("[sagaId={}] Starting saga type={}", instance.getSagaId(), request.sagaType());
        dispatchStep(instance, definition);
        return instance;
    }

    public void handleReply(String sagaId, String stepId, boolean success, String failureType, Object data) {
        SagaInstance instance = repository.findBySagaId(sagaId)
                .orElseThrow(() -> new SagaNotFoundException(sagaId));
        SagaDefinition definition = definitionLoader.getDefinition(instance.getSagaType());

        int stepIndex = instance.getCurrentStep();
        if (stepIndex >= definition.steps().size()) {
            log.warn("[sagaId={}] Reply for already completed saga — ignoring", sagaId);
            return;
        }

        StepDefinition currentStep = definition.steps().get(stepIndex);
        if (!currentStep.name().equals(stepId)) {
            log.warn("[sagaId={}] Reply stepId={} != current step={} — ignoring",
                    sagaId, stepId, currentStep.name());
            return;
        }

        if (success) {
            instance = stateMachine.completeStep(instance, currentStep, data, definition);
            if (instance.getStatus() == SagaStatus.COMPLETED) {
                releaseLock(instance);
            } else {
                dispatchStep(instance, definition);
            }
        } else if ("BUSINESS".equals(failureType)) {
            instance = stateMachine.startCompensation(instance, "Business failure at step: " + stepId);
            compensate(instance, definition);
        } else {
            instance = stateMachine.suspend(instance, "Technical failure at step: " + stepId);
        }
    }

    public void retryFromSuspended(String sagaId) {
        SagaInstance instance = repository.findBySagaId(sagaId)
                .orElseThrow(() -> new SagaNotFoundException(sagaId));
        stateMachine.validateForRetry(instance);
        SagaDefinition definition = definitionLoader.getDefinition(instance.getSagaType());

        instance.setStatus(SagaStatus.IN_PROGRESS);
        instance = checkpointStore.save(instance);
        checkpointStore.logEvent(instance.getSagaId(), "SAGA", "RETRY_FROM_SUSPENDED", null);
        dispatchStep(instance, definition);
    }

    public void triggerCompensation(String sagaId) {
        SagaInstance instance = repository.findBySagaId(sagaId)
                .orElseThrow(() -> new SagaNotFoundException(sagaId));
        stateMachine.validateForCompensation(instance);
        SagaDefinition definition = definitionLoader.getDefinition(instance.getSagaType());
        instance = stateMachine.startCompensation(instance, "Manual trigger");
        compensate(instance, definition);
    }

    private void dispatchStep(SagaInstance instance, SagaDefinition definition) {
        int stepIndex = instance.getCurrentStep();
        if (stepIndex >= definition.steps().size()) return;
        StepDefinition step = definition.steps().get(stepIndex);
        stateMachine.startStep(instance, step);

        StepResult result = executorRegistry.getExecutor(step.type()).execute(instance, step);

        if (result.isDispatched()) return; // Kafka — async reply via ReplyCorrelator

        // HTTP — handle inline
        if (result.isSuccess()) {
            instance = stateMachine.completeStep(instance, step, result.data(), definition);
            if (instance.getStatus() == SagaStatus.COMPLETED) {
                releaseLock(instance);
            } else {
                dispatchStep(instance, definition);
            }
        } else if (result.isBusinessFailure()) {
            instance = stateMachine.startCompensation(instance, "Business failure at HTTP step: " + step.name());
            compensate(instance, definition);
        } else {
            instance = stateMachine.suspend(instance,
                    "Technical failure at HTTP step: " + step.name() + " — " + result.error());
        }
    }

    private void compensate(SagaInstance instance, SagaDefinition definition) {
        int startFrom = instance.getCurrentStep() - 1;
        for (int i = startFrom; i >= 0; i--) {
            StepDefinition step = definition.steps().get(i);
            if (!step.hasCompensation()) continue;
            try {
                executorRegistry.getExecutor(step.type()).compensate(instance, step);
                instance = stateMachine.completeCompensationStep(instance, step);
            } catch (Exception e) {
                log.error("[sagaId={}] Compensation step {} failed: {}",
                        instance.getSagaId(), step.name(), e.getMessage());
                instance = stateMachine.failCompensationStep(instance, step, e.getMessage());
            }
        }
        stateMachine.finishCompensation(instance);
        releaseLock(instance); // Release on COMPENSATED or FAILED
    }

    private void acquireLockIfNeeded(SagaInstance instance, SagaDefinition definition) {
        if (!definition.hasLockTarget()) return;
        Map<String, Object> payload = instance.getPayload();
        Object targetId = payload.get(definition.lockTargetField());
        if (targetId == null) {
            log.warn("[sagaId={}] Lock target field '{}' not found in payload — skipping lock",
                    instance.getSagaId(), definition.lockTargetField());
            return;
        }
        lockManager.acquire(
                definition.lockTargetType(),
                targetId.toString(),
                instance.getSagaId(),
                instance.getSagaType(),
                Duration.ofMinutes(definition.timeoutMinutes()));
    }

    private void releaseLock(SagaInstance instance) {
        try {
            lockManager.releaseAll(instance.getSagaId());
        } catch (Exception e) {
            log.warn("[sagaId={}] Failed to release locks: {}", instance.getSagaId(), e.getMessage());
        }
    }
}
