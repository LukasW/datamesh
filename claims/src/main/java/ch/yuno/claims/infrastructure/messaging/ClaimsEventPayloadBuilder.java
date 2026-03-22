package ch.yuno.claims.infrastructure.messaging;

import ch.yuno.claims.domain.model.Claim;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Builds JSON payloads for outbox events.
 * Manual JSON construction avoids a Jackson dependency in the infrastructure layer.
 */
public final class ClaimsEventPayloadBuilder {

    public static final String TOPIC_CLAIM_OPENED  = "claims.v1.opened";
    public static final String TOPIC_CLAIM_SETTLED = "claims.v1.settled";

    private ClaimsEventPayloadBuilder() {}

    public static String buildClaimOpened(Claim claim) {
        return """
                {"eventId":"%s","eventType":"ClaimOpened","claimId":"%s","claimNumber":"%s","policyId":"%s","description":"%s","claimDate":"%s","status":"%s","timestamp":"%s"}"""
                .formatted(
                        UUID.randomUUID(),
                        claim.getClaimId(),
                        claim.getClaimNumber(),
                        claim.getPolicyId(),
                        escapeJson(claim.getDescription()),
                        claim.getClaimDate(),
                        claim.getStatus().name(),
                        OffsetDateTime.now()
                );
    }

    public static String buildClaimSettled(Claim claim) {
        return """
                {"eventId":"%s","eventType":"ClaimSettled","claimId":"%s","claimNumber":"%s","policyId":"%s","claimDate":"%s","status":"%s","timestamp":"%s"}"""
                .formatted(
                        UUID.randomUUID(),
                        claim.getClaimId(),
                        claim.getClaimNumber(),
                        claim.getPolicyId(),
                        claim.getClaimDate(),
                        claim.getStatus().name(),
                        OffsetDateTime.now()
                );
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
