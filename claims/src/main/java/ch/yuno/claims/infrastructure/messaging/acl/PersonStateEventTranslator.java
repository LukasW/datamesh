package ch.yuno.claims.infrastructure.messaging.acl;

import ch.yuno.claims.domain.model.PartnerSearchView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;

/**
 * ACL: Translates person.v1.state JSON into Claims domain model.
 * Isolates the Claims domain from Partner schema changes.
 */
public class PersonStateEventTranslator {

    private final ObjectMapper objectMapper;

    public PersonStateEventTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public sealed interface TranslationResult {
        record PartnerUpsert(PartnerSearchView view) implements TranslationResult {}
        record PartnerDeletion(String partnerId) implements TranslationResult {}
    }

    /**
     * Translates a person.v1.state JSON payload into either an upsert or deletion command.
     */
    public TranslationResult translate(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);

        String partnerId = json.path("personId").asText(null);
        if (partnerId == null || partnerId.isBlank()) {
            throw new IllegalArgumentException("person.v1.state event missing personId");
        }

        boolean deleted = json.path("deleted").asBoolean(false);
        if (deleted) {
            return new TranslationResult.PartnerDeletion(partnerId);
        }

        String lastName = json.path("name").asText(null);
        String firstName = json.path("firstName").asText(null);
        if (lastName == null || lastName.isBlank() || firstName == null || firstName.isBlank()) {
            throw new IllegalArgumentException(
                    "person.v1.state event missing name/firstName for personId=" + partnerId);
        }

        String dobStr = json.path("dateOfBirth").asText(null);
        LocalDate dateOfBirth = (dobStr != null && !dobStr.isBlank()) ? LocalDate.parse(dobStr) : null;

        String socialSecurityNumber = json.path("socialSecurityNumber").asText(null);
        String insuredNumber = json.path("insuredNumber").asText(null);

        return new TranslationResult.PartnerUpsert(new PartnerSearchView(
                partnerId, lastName, firstName, dateOfBirth, socialSecurityNumber, insuredNumber));
    }
}

