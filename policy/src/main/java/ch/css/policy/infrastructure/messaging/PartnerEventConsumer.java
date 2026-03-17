package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.PartnerView;
import ch.css.policy.domain.port.out.PartnerViewRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
 */
@ApplicationScoped
public class PartnerEventConsumer {

    private static final Logger log = Logger.getLogger(PartnerEventConsumer.class);

    @Inject
    PartnerViewRepository partnerViewRepository;

    @Inject
    ObjectMapper objectMapper;

    @Incoming("partner-person-created")
    @Transactional
    public void onPersonCreated(String payload) {
        log.infof("Received Kafka event [person.v1.created]");
        upsertPartnerFromJson(payload);
    }

    @Incoming("partner-person-updated")
    @Transactional
    public void onPersonUpdated(String payload) {
        log.infof("Received Kafka event [person.v1.updated]");
        upsertPartnerFromJson(payload);
    }

    private void upsertPartnerFromJson(String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            String partnerId = json.path("personId").asText(null);
            String firstName = json.path("firstName").asText("");
            String name      = json.path("name").asText("");
            String fullName  = (firstName + " " + name).trim();
            if (partnerId == null || partnerId.isEmpty() || fullName.isEmpty()) {
                log.warnf("Partner event missing required fields: personId=%s name=%s", partnerId, fullName);
                return;
            }
            partnerViewRepository.upsert(new PartnerView(partnerId, fullName));
            log.infof("PartnerView upserted: %s -> %s", partnerId, fullName);
        } catch (Exception e) {
            log.errorf("Failed to process partner event: %s", e.getMessage());
        }
    }
}
