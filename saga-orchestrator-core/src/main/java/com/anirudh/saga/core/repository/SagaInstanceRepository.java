package com.anirudh.saga.core.repository;

import com.anirudh.saga.core.domain.SagaInstance;
import com.anirudh.saga.core.domain.SagaStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SagaInstanceRepository extends MongoRepository<SagaInstance, String> {

    Optional<SagaInstance> findBySagaId(String sagaId);

    Optional<SagaInstance> findByIdempotencyKey(String idempotencyKey);

    List<SagaInstance> findByStatus(SagaStatus status);

    @Query("{ 'status': { $in: ['STARTED', 'IN_PROGRESS'] }, 'timeoutAt': { $lt: ?0 } }")
    List<SagaInstance> findTimedOut(Instant now);
}
