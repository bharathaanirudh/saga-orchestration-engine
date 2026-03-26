package com.anirudh.saga.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class SagaMetrics {

    private final Counter sagaStarted;
    private final Counter sagaCompleted;
    private final Counter sagaCompensated;
    private final Counter sagaFailed;
    private final Counter sagaSuspended;
    private final Counter stepExecuted;
    private final Counter stepFailed;
    private final Timer sagaDuration;

    public SagaMetrics(MeterRegistry registry) {
        this.sagaStarted = Counter.builder("saga.started")
                .description("Total sagas started").register(registry);
        this.sagaCompleted = Counter.builder("saga.completed")
                .description("Total sagas completed successfully").register(registry);
        this.sagaCompensated = Counter.builder("saga.compensated")
                .description("Total sagas compensated").register(registry);
        this.sagaFailed = Counter.builder("saga.failed")
                .description("Total sagas failed (compensation failure)").register(registry);
        this.sagaSuspended = Counter.builder("saga.suspended")
                .description("Total sagas suspended (technical failure)").register(registry);
        this.stepExecuted = Counter.builder("saga.step.executed")
                .description("Total steps executed").register(registry);
        this.stepFailed = Counter.builder("saga.step.failed")
                .description("Total steps failed").register(registry);
        this.sagaDuration = Timer.builder("saga.duration")
                .description("Saga execution duration").register(registry);
    }

    public void recordStarted() { sagaStarted.increment(); }
    public void recordCompleted() { sagaCompleted.increment(); }
    public void recordCompensated() { sagaCompensated.increment(); }
    public void recordFailed() { sagaFailed.increment(); }
    public void recordSuspended() { sagaSuspended.increment(); }
    public void recordStepExecuted() { stepExecuted.increment(); }
    public void recordStepFailed() { stepFailed.increment(); }
    public Timer.Sample startTimer() { return Timer.start(); }
    public void stopTimer(Timer.Sample sample) { sample.stop(sagaDuration); }
}
