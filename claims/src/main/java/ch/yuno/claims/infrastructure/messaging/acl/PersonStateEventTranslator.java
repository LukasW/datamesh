package ch.yuno.claims.infrastructure.messaging.acl;

import ch.yuno.claims.domain.model.PartnerSearchView;
import ch.yuno.claims.domain.port.out.PiiDecryptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;

/**
 * ACL: Translates person.v1.state JSON into Claims domain model.
 * Isolates the Claims domain from Partner schema changes.
 * Handles decryption of PII fields encrypted via Vault Transit (ADR-009).
 */
public class PersonStateEventTranslator {

    private final ObjectMapper objectMapper;
    private final PiiDecryptor piiDecryptor;

    public PersonStateEventTranslator(ObjectMapper objectMapper, PiiDecryptor piiDecryptor) {
        this.objectMapper = objectMapper;
        this.piiDecryptor = piiDecryptor;
    }

    public sealed interface TranslationResult {
        record PartnerUpsert(PartnerSearchView view) implements TranslationResult {}
        record PartnerDeletion(String partnerId) implements TranslationResult {}
    }

    /**
     * Translates a person.v1.state JSON payload into either an upsert or deletion command.
     * If the event is encrypted (ADR-009), PII fields are decrypted via Vault Transit.
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

        boolean encrypted = json.path("encrypted").asBoolean(false);

        String lastName = json.path("name").asText(null);
        String firstName = json.path("firstName").asText(null);
        String dobStr = json.path("dateOfBirth").asText(null);
        String socialSecurityNumber = json.path("socialSecurityNumber").asText(null);

        if (encrypted) {
            lastName = piiDecryptor.decrypt(partnerId, lastName);
            firstName = piiDecryptor.decrypt(partnerId, firstName);
            dobStr = piiDecryptor.decrypt(partnerId, dobStr);
            socialSecurityNumber = piiDecryptor.decrypt(partnerId, socialSecurityNumber);
        }

        if (lastName == null || lastName.isBlank() || firstName == null || firstName.isBlank()) {
            throw new IllegalArgumentException(
                    "person.v1.state event missing name/firstName for personId=" + partnerId);
        }

        LocalDate dateOfBirth = (dobStr != null && !dobStr.isBlank()) ? LocalDate.parse(dobStr) : null;

        // insuredNumber is NOT PII – never encrypted
        String insuredNumber = json.path("insuredNumber").asText(null);

        return new TranslationResult.PartnerUpsert(new PartnerSearchView(
                partnerId, lastName, firstName, dateOfBirth, socialSecurityNumber, insuredNumber));
    }
}

