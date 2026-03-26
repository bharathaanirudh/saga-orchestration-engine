package com.anirudh.saga.core.domain;

public class SagaStep {
    private String name;
    private StepType type;
    private String action;
    private String topic;
    private String url;
    private boolean completed;

    public SagaStep() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public StepType getType() { return type; }
    public void setType(StepType type) { this.type = type; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
}
