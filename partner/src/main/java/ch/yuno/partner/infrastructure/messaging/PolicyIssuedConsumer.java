package ch.yuno.partner.infrastructure.messaging;

import ch.yuno.partner.domain.model.PersonId;
import ch.yuno.partner.domain.port.in.PersonCommandUseCase;
import ch.yuno.partner.application.PersonNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Kafka consumer for policy.v1.issued events from the Policy domain.
 * Assigns an insured number to the referenced partner if they don't have one yet.
 * Idempotent: re-delivery of the same event has no effect if the partner is already insured.
 *
 * This is the first Kafka consumer in the Partner Service – the service was previously
 * a pure event producer.
 */
@ApplicationScoped
public class PolicyIssuedConsumer {

    private static final Logger LOG = Logger.getLogger(PolicyIssuedConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    PersonCommandUseCase personCommandService;

    @Incoming("policy-issued-in")
    @Transactional
    public void onPolicyIssued(String payload) {
        try {
            JsonNode json = MAPPER.readTree(payload);
            String partnerId = json.path("partnerId").asText(null);

            if (partnerId == null || partnerId.isBlank()) {
                LOG.warnf("policy.v1.issued event missing partnerId – skipping: %s",
                        payload.substring(0, Math.min(200, payload.length())));
                return;
            }

            boolean assigned = personCommandService.assignInsuredNumberIfAbsent(PersonId.of(partnerId));
            if (assigned) {
                LOG.infof("Assigned insured number to partner %s via policy.v1.issued", partnerId);
            } else {
                LOG.debugf("Partner %s already has insured number – skipping", partnerId);
            }
        } catch (PersonNotFoundException e) {
            LOG.warnf("Partner not found for policy.v1.issued event: %s", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Failed to process policy.v1.issued event: %s", e.getMessage());
            throw new RuntimeException("Failed to process policy.v1.issued", e);
        }
    }
}

