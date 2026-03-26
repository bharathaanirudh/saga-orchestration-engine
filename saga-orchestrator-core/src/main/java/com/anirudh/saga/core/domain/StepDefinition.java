package com.anirudh.saga.core.domain;

public record StepDefinition(
        String name,
        StepType type,
        String action,
        String module,
        String topic,
        String url,
        String compensationAction,
        String compensationTopic,
        String compensationUrl,
        int retryMaxAttempts,
        int timeoutSeconds
) {
    /**
     * Resolved topic — if module is set, derives {module}-commands.
     * Explicit topic overrides convention.
     */
    public String resolvedTopic() {
        if (topic != null && !topic.isBlank()) return topic;
        if (module != null && !module.isBlank()) return module + "-commands";
        return null;
    }

    /**
     * Resolved compensation topic — same convention from module, or explicit override.
     */
    public String resolvedCompensationTopic() {
        if (compensationTopic != null && !compensationTopic.isBlank()) return compensationTopic;
        if (module != null && !module.isBlank()) return module + "-commands";
        return null;
    }

    public boolean hasCompensation() {
        return compensationAction != null && !compensationAction.isBlank();
    }
}
