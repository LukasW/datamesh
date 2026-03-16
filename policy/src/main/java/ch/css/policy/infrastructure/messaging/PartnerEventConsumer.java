package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.PartnerView;
import ch.css.policy.domain.port.out.PartnerViewRepository;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Kafka consumer for Partner domain events.
 * Materializes the local PartnerView read model from person.v1.created
 * and person.v1.updated events (ADR-001: Kafka is the only cross-domain channel).
 */
@ApplicationScoped
public class PartnerEventConsumer {

    private static final Logger log = Logger.getLogger(PartnerEventConsumer.class);

    @Inject
    PartnerViewRepository partnerViewRepository;

    @Incoming("partner-person-created")
    @Transactional
    public void onPersonCreated(String json) {
        log.infof("Received Kafka event [person.v1.created]");
        upsertPartnerFromEvent(json);
    }

    @Incoming("partner-person-updated")
    @Transactional
    public void onPersonUpdated(String json) {
        log.infof("Received Kafka event [person.v1.updated]");
        upsertPartnerFromEvent(json);
    }

    private void upsertPartnerFromEvent(String json) {
        try {
            JsonObject event = new JsonObject(json);
            String partnerId = event.getString("personId");
            String firstName = event.getString("firstName", "");
            String name      = event.getString("name", "");
            String fullName  = (firstName + " " + name).trim();
            if (partnerId == null || fullName.isEmpty()) {
                log.warnf("Partner event missing required fields: %s", json);
                return;
            }
            partnerViewRepository.upsert(new PartnerView(partnerId, fullName));
            log.infof("PartnerView upserted: %s -> %s", partnerId, fullName);
        } catch (Exception e) {
            log.errorf("Failed to process partner event: %s | %s", e.getMessage(), json);
        }
    }
}
