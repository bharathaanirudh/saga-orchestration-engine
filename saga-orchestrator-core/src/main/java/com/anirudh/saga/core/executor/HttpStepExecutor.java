package com.anirudh.saga.core.executor;

import com.anirudh.saga.core.domain.SagaInstance;
import com.anirudh.saga.core.domain.StepDefinition;
import com.anirudh.saga.core.domain.StepType;
import com.anirudh.saga.core.exception.SagaExecutionException;
import com.anirudh.saga.sdk.contract.SagaHttpCommand;
import com.anirudh.saga.sdk.contract.SagaHttpReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpStepExecutor.class);

    private final RestClient restClient;

    public HttpStepExecutor(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public StepType supports() {
        return StepType.HTTP;
    }

    @Override
    public StepResult execute(SagaInstance instance, StepDefinition step) {
        log.info("[sagaId={}] Dispatching HTTP step={} url={}",
                instance.getSagaId(), step.name(), step.url());
        SagaHttpCommand command = new SagaHttpCommand(
                instance.getSagaId(), step.name(), step.action(), instance.getPayload());
        try {
            SagaHttpReply reply = post(step.url(), command);
            if (reply == null) {
                return StepResult.technicalFailure("HTTP step " + step.name() + " returned null reply");
            }
            if (reply.isSuccess()) {
                return StepResult.success(reply.data());
            }
            if (reply.isBusinessFailure()) {
                return StepResult.businessFailure(reply.error());
            }
            return StepResult.technicalFailure(reply.error());
        } catch (Exception e) {
            log.error("[sagaId={}] HTTP step {} failed: {}",
                    instance.getSagaId(), step.name(), e.getMessage());
            return StepResult.technicalFailure("HTTP call failed to " + step.url() + ": " + e.getMessage());
        }
    }

    @Override
    public void compensate(SagaInstance instance, StepDefinition step) {
        log.info("[sagaId={}] Dispatching HTTP compensation step={} url={}",
                instance.getSagaId(), step.name(), step.compensationUrl());
        SagaHttpCommand command = new SagaHttpCommand(
                instance.getSagaId(), step.name(), step.compensationAction(), instance.getContext());
        SagaHttpReply reply = post(step.compensationUrl(), command);
        if (reply == null || !reply.isSuccess()) {
            String error = reply != null ? reply.error() : "null reply";
            throw new SagaExecutionException(
                    "HTTP compensation failed for step " + step.name() + ": " + error, null);
        }
    }

    private SagaHttpReply post(String url, SagaHttpCommand command) {
        try {
            return restClient.post()
                    .uri(url)
                    .body(command)
                    .retrieve()
                    .body(SagaHttpReply.class);
        } catch (Exception e) {
            throw new SagaExecutionException("HTTP call failed to " + url, e);
        }
    }
}
