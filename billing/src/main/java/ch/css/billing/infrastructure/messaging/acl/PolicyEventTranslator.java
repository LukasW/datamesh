package ch.css.billing.infrastructure.messaging.acl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/**
 * Anti-Corruption Layer: translates external Policy domain event JSON
 * into billing domain primitives. Isolates billing from Policy schema changes.
 */
public final class PolicyEventTranslator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PolicyEventTranslator() {}

    public record PolicyIssuedData(
            String policyId,
            String policyNumber,
            String partnerId,
            BigDecimal premium
    ) {}

    public record PolicyCancelledData(String policyId) {}

    public static PolicyIssuedData translateIssued(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String policyId    = required(node, "policyId");
            String policyNumber = required(node, "policyNumber");
            String partnerId   = required(node, "partnerId");
            String premiumStr  = required(node, "premium");
            return new PolicyIssuedData(policyId, policyNumber, partnerId, new BigDecimal(premiumStr));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot translate PolicyIssued event: " + e.getMessage(), e);
        }
    }

    public static PolicyCancelledData translateCancelled(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return new PolicyCancelledData(required(node, "policyId"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot translate PolicyCancelled event: " + e.getMessage(), e);
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Required field missing: " + field);
        }
        return value.asText();
    }
}
