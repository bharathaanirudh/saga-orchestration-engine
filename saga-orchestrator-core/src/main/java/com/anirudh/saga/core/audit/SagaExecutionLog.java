package com.anirudh.saga.core.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document("saga_execution_log")
public class SagaExecutionLog {

    @Id
    private String id;
    private String sagaId;
    private String stepName;
    private String event;
    private String data;
    private Instant timestamp;

    public SagaExecutionLog() {}

    public static SagaExecutionLog of(String sagaId, String stepName, String event, String data, Instant now) {
        SagaExecutionLog log = new SagaExecutionLog();
        log.id = UUID.randomUUID().toString();
        log.sagaId = sagaId;
        log.stepName = stepName;
        log.event = event;
        log.data = data;
        log.timestamp = now;
        return log;
    }

    public String getId() { return id; }
    public String getSagaId() { return sagaId; }
    public void setSagaId(String sagaId) { this.sagaId = sagaId; }
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
