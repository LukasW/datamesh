package ch.yuno.policy.infrastructure.messaging.acl;

import ch.yuno.policy.domain.model.PartnerView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Anti-Corruption Layer: translates external Partner domain events (PersonCreated/PersonUpdated)
 * into the local PartnerView read model. Isolates the Policy domain from changes in the
 * Partner domain's event schema.
 */
public class PartnerEventTranslator {

    private final ObjectMapper objectMapper;

    public PartnerEventTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Translates a person event JSON payload into a PartnerView.
     *
     * @param payload JSON string from person.v1.created or person.v1.updated
     * @return PartnerView with partnerId and full name
     * @throws IllegalArgumentException if required fields are missing
     */
    public PartnerView translate(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String partnerId = json.path("personId").asText(null);
        String firstName = json.path("firstName").asText("");
        String name = json.path("name").asText("");
        String fullName = (firstName + " " + name).trim();
        String insuredNumber = json.path("insuredNumber").asText(null);

        if (partnerId == null || partnerId.isEmpty() || fullName.isEmpty()) {
            throw new IllegalArgumentException(
                    "Partner event missing required fields: personId=" + partnerId + " name=" + fullName);
        }

        return new PartnerView(partnerId, fullName, insuredNumber);
    }
}
