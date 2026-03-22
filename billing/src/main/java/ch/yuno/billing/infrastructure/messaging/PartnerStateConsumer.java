package ch.yuno.billing.infrastructure.messaging;

import ch.yuno.billing.domain.model.PolicyholderView;
import ch.yuno.billing.domain.port.out.PolicyholderViewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Kafka consumer for partner.v1.state (ECST compacted topic).
 * Materialises a local PolicyholderView read model for invoice display.
 */
@ApplicationScoped
public class PartnerStateConsumer {

    private static final Logger log = Logger.getLogger(PartnerStateConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    PolicyholderViewRepository policyholderViewRepository;

    @Transactional
    @Incoming("partner-state-in")
    public void onPersonState(String message) {
        try {
            JsonNode node = MAPPER.readTree(message);
            String personId = node.path("personId").asText(null);
            if (personId == null || personId.isBlank()) return;

            boolean deleted = node.path("deleted").asBoolean(false);
            if (deleted) {
                log.infof("Skipping deleted person: %s", personId);
                return;
            }

            String firstName = node.path("firstName").asText("");
            String lastName  = node.path("name").asText("");
            String fullName  = (firstName + " " + lastName).trim();
            if (fullName.isBlank()) fullName = personId;

            policyholderViewRepository.upsert(new PolicyholderView(personId, fullName));
            log.infof("Upserted PolicyholderView: %s → %s", personId, fullName);
        } catch (Exception e) {
            log.errorf("Failed to process person.v1.state: %s", e.getMessage());
        }
    }
}
