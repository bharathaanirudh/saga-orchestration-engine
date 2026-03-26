package com.anirudh.saga.core.unit.executor;

import com.anirudh.saga.core.executor.HttpStepExecutor;
import com.anirudh.saga.core.executor.StepResult;
import com.anirudh.saga.core.fixtures.SagaDefinitionFixture;
import com.anirudh.saga.core.fixtures.SagaInstanceFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpStepExecutorTest {

    @Mock RestClient.Builder restClientBuilder;
    @Mock RestClient restClient;

    HttpStepExecutor executor;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.build()).thenReturn(restClient);
        executor = new HttpStepExecutor(restClientBuilder);
    }

    @Test
    void supports_returnsHttp() {
        assertThat(executor.supports().name()).isEqualTo("HTTP");
    }

    @Test
    void execute_whenRestClientThrows_returnsTechnicalFailure() {
        when(restClient.post()).thenThrow(new RuntimeException("Connection refused"));
        StepResult result = executor.execute(SagaInstanceFixture.inProgress(),
                SagaDefinitionFixture.httpStep("pay", "CHARGE", "http://localhost/pay", "REFUND", "http://localhost/refund"));
        assertThat(result.isTechnicalFailure()).isTrue();
        assertThat(result.error()).contains("HTTP call failed");
    }
}
