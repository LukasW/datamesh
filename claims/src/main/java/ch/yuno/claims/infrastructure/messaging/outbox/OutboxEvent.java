package ch.yuno.claims.infrastructure.messaging.outbox;

import java.util.UUID;

/**
 * Value object representing a single outbox entry to be picked up by Debezium CDC.
 */
public record OutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String topic,
        String payload
) {}
