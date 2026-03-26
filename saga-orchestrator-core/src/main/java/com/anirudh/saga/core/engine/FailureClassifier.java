package com.anirudh.saga.core.engine;

import com.anirudh.saga.sdk.contract.FailureType;
import org.springframework.stereotype.Component;

@Component
public class FailureClassifier {

    public static final String HEADER_FAILURE_TYPE = "X-Failure-Type";

    public boolean isBusinessFailure(String failureType) {
        return FailureType.BUSINESS.name().equals(failureType);
    }

    public boolean isTechnicalFailure(String failureType) {
        return FailureType.TECHNICAL.name().equals(failureType);
    }
}
