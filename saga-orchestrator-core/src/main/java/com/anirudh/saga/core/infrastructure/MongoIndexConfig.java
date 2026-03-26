package com.anirudh.saga.core.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class MongoIndexConfig implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexConfig.class);

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        log.info("Ensuring MongoDB indexes...");

        // saga_instances
        mongoTemplate.indexOps("saga_instances")
                .ensureIndex(new Index("idempotencyKey", Sort.Direction.ASC).unique().named("idx_idempotency_key"));
        mongoTemplate.indexOps("saga_instances")
                .ensureIndex(new Index("status", Sort.Direction.ASC).named("idx_status"));
        mongoTemplate.indexOps("saga_instances")
                .ensureIndex(new CompoundIndexDefinition(
                        new org.bson.Document("status", 1).append("timeoutAt", 1)
                ).named("idx_status_timeout"));

        // saga_execution_log
        mongoTemplate.indexOps("saga_execution_log")
                .ensureIndex(new Index("sagaId", Sort.Direction.ASC).named("idx_execution_log_sagaid"));

        // saga_locks — expiresAt for cleanup scheduler
        mongoTemplate.indexOps("saga_locks")
                .ensureIndex(new Index("expiresAt", Sort.Direction.ASC).named("idx_lock_expires"));
        mongoTemplate.indexOps("saga_locks")
                .ensureIndex(new Index("sagaId", Sort.Direction.ASC).named("idx_lock_sagaid"));

        // saga_processed_commands — TTL index (auto-delete after 7 days)
        mongoTemplate.indexOps("saga_processed_commands")
                .ensureIndex(new Index("processedAt", Sort.Direction.ASC)
                        .expire(java.time.Duration.ofDays(7)).named("idx_processed_ttl"));

        log.info("MongoDB indexes ensured.");
    }
}
