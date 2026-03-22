package ch.yuno.partner.infrastructure.messaging.outbox;

import java.util.UUID;

/**
 * Data transfer object representing an event to be written to the transactional outbox table.
 * Debezium reads rows from this table via PostgreSQL WAL and publishes them to Kafka.
 * No framework dependencies.
 *
 * NOTE: This class lives in infrastructure (not domain) intentionally.
 * The OutboxRepository port in domain/port/out references this type, which is an accepted
 * interim violation to be resolved in TASK-13 by introducing typed domain events.
 */
public class OutboxEvent {

    private final UUID id;
    private final String aggregateType;
    private final String aggregateId;
    private final String eventType;
    private final String topic;
    private final String payload;

    public OutboxEvent(UUID id, String aggregateType, String aggregateId,
                       String eventType, String topic, String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
}
