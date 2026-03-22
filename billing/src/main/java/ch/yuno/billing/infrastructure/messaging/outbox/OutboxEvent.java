package ch.yuno.billing.infrastructure.messaging.outbox;

import java.util.UUID;

/**
 * Value object representing an event to be persisted in the outbox table.
 * Debezium CDC picks it up from the WAL and publishes it to Kafka.
 */
public record OutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String topic,
        String payload
) {}
