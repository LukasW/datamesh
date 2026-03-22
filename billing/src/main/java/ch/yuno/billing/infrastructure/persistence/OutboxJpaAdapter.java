package ch.yuno.billing.infrastructure.persistence;

import ch.yuno.billing.domain.port.out.OutboxRepository;
import ch.yuno.billing.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class OutboxJpaAdapter implements OutboxRepository {

    @Inject
    EntityManager em;

    @Override
    public void save(OutboxEvent event) {
        OutboxEntity entity = new OutboxEntity();
        entity.setId(event.id());
        entity.setAggregateType(event.aggregateType());
        entity.setAggregateId(event.aggregateId());
        entity.setEventType(event.eventType());
        entity.setTopic(event.topic());
        entity.setPayload(event.payload());
        em.persist(entity);
    }
}
