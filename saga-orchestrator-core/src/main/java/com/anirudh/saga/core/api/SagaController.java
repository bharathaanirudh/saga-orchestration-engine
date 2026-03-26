package com.anirudh.saga.core.api;

import com.anirudh.saga.core.audit.SagaExecutionLog;
import com.anirudh.saga.core.domain.SagaInstance;
import com.anirudh.saga.core.domain.SagaStatus;
import com.anirudh.saga.core.engine.SagaOrchestrator;
import com.anirudh.saga.core.exception.SagaNotFoundException;
import com.anirudh.saga.core.repository.SagaExecutionLogRepository;
import com.anirudh.saga.core.repository.SagaInstanceRepository;
import com.anirudh.saga.sdk.contract.SagaStartRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sagas")
public class SagaController {

    private static final Logger log = LoggerFactory.getLogger(SagaController.class);

    private final SagaInstanceRepository repository;
    private final SagaExecutionLogRepository logRepository;
    private final SagaOrchestrator orchestrator;

    public SagaController(SagaInstanceRepository repository,
                          SagaExecutionLogRepository logRepository,
                          SagaOrchestrator orchestrator) {
        this.repository = repository;
        this.logRepository = logRepository;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public SagaResponse<SagaInstance> start(@RequestBody SagaStartRequest request) {
        log.info("Starting saga type={}", request.sagaType());
        SagaInstance instance = orchestrator.start(request);
        return SagaResponse.ok(instance);
    }

    @GetMapping("/{sagaId}")
    public SagaResponse<Map<String, Object>> get(@PathVariable String sagaId) {
        SagaInstance instance = repository.findBySagaId(sagaId)
                .orElseThrow(() -> new SagaNotFoundException(sagaId));
        List<SagaExecutionLog> timeline = logRepository.findBySagaIdOrderByTimestampAsc(sagaId);
        return SagaResponse.ok(Map.of("saga", instance, "timeline", timeline));
    }

    @GetMapping
    public SagaResponse<List<SagaInstance>> list(@RequestParam(required = false) String status) {
        List<SagaInstance> results;
        if (status != null) {
            results = repository.findByStatus(SagaStatus.valueOf(status.toUpperCase()));
        } else {
            results = repository.findAll();
        }
        return SagaResponse.ok(results);
    }

    @PostMapping("/{sagaId}/retry")
    public SagaResponse<String> retry(@PathVariable String sagaId) {
        log.info("[sagaId={}] Manual retry requested", sagaId);
        orchestrator.retryFromSuspended(sagaId);
        return SagaResponse.ok("Saga retry triggered");
    }

    @PostMapping("/{sagaId}/compensate")
    public SagaResponse<String> compensate(@PathVariable String sagaId) {
        log.info("[sagaId={}] Manual compensation requested", sagaId);
        orchestrator.triggerCompensation(sagaId);
        return SagaResponse.ok("Saga compensation triggered");
    }
}
