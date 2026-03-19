package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.PartnerView;
import ch.css.policy.domain.port.out.PartnerViewRepository;
import ch.css.policy.infrastructure.messaging.acl.PartnerEventTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Kafka consumer for Partner domain events.
 * Materializes the local PartnerView read model from person.v1.created
 * and person.v1.updated events (ADR-001: Kafka is the only cross-domain channel).
 * Messages arrive as JSON strings published by Debezium via StringConverter.
 *
 * Translation from external Partner schema to local PartnerView is delegated
 * to the Anti-Corruption Layer ({@link PartnerEventTranslator}).
 *
 * Failed messages are routed to a dead-letter-queue topic for later inspection.
 */
@ApplicationScoped
public class PartnerEventConsumer {

    private static final Logger log = Logger.getLogger(PartnerEventConsumer.class);

    @Inject
    PartnerViewRepository partnerViewRepository;

    @Inject
    ObjectMapper objectMapper;

    private PartnerEventTranslator translator;

    PartnerEventTranslator getTranslator() {
        if (translator == null) {
            translator = new PartnerEventTranslator(objectMapper);
        }
        return translator;
    }

    @Incoming("partner-person-created")
    @Transactional
    public void onPersonCreated(String payload) {
        log.infof("Received Kafka event [person.v1.created]");
        try {
            PartnerView view = getTranslator().translate(payload);
            partnerViewRepository.upsert(view);
            log.infof("PartnerView upserted: %s -> %s", view.getPartnerId(), view.getName());
        } catch (Exception e) {
            log.errorf(e, "Failed to process person.v1.created event, routing to DLQ. Payload: %s", payload);
            throw new RuntimeException("Failed to process person.v1.created event: " + e.getMessage(), e);
        }
    }

    @Incoming("partner-person-updated")
    @Transactional
    public void onPersonUpdated(String payload) {
        log.infof("Received Kafka event [person.v1.updated]");
        try {
            PartnerView view = getTranslator().translate(payload);
            partnerViewRepository.upsert(view);
            log.infof("PartnerView upserted: %s -> %s", view.getPartnerId(), view.getName());
        } catch (Exception e) {
            log.errorf(e, "Failed to process person.v1.updated event, routing to DLQ. Payload: %s", payload);
            throw new RuntimeException("Failed to process person.v1.updated event: " + e.getMessage(), e);
        }
    }
}
