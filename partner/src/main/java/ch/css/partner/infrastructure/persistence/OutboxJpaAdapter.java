package ch.css.partner.infrastructure.persistence;

import ch.css.partner.infrastructure.messaging.outbox.OutboxEvent;
import ch.css.partner.domain.port.out.OutboxRepository;
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
        entity.setId(event.getId());
        entity.setAggregateType(event.getAggregateType());
        entity.setAggregateId(event.getAggregateId());
        entity.setEventType(event.getEventType());
        entity.setTopic(event.getTopic());
        entity.setPayload(event.getPayload());
        em.persist(entity);
    }
}
