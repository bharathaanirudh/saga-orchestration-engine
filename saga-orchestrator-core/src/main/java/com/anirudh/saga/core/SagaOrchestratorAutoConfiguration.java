package com.anirudh.saga.core;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableAutoConfiguration(exclude = {
        MongoAutoConfiguration.class,
        MongoDataAutoConfiguration.class,
        KafkaAutoConfiguration.class
})
@EnableScheduling
@ComponentScan(basePackages = "com.anirudh.saga.core")
public class SagaOrchestratorAutoConfiguration {
    // All beans now in dedicated config classes:
    // MongoConfig — MongoDB client, transaction manager, repository scanning
    // KafkaConfig — producer, consumer, listener container factories
    // ClockConfig — injectable Clock bean
}
