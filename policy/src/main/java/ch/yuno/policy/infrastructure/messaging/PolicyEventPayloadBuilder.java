package ch.yuno.policy.infrastructure.messaging;

import ch.yuno.policy.domain.model.CoverageType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Builds JSON payloads for policy domain events written to the outbox table.
 * Produces byte-for-byte identical output to the former PolicyKafkaAdapter,
 * ensuring downstream consumers require no changes.
 * No framework dependencies.
 */
public final class PolicyEventPayloadBuilder {

    public static final String TOPIC_POLICY_ISSUED          = "policy.v1.issued";
    public static final String TOPIC_POLICY_CANCELLED       = "policy.v1.cancelled";
    public static final String TOPIC_POLICY_CHANGED         = "policy.v1.changed";
    public static final String TOPIC_COVERAGE_ADDED         = "policy.v1.coverage-added";
    public static final String TOPIC_COVERAGE_REMOVED       = "policy.v1.coverage-removed";

    private PolicyEventPayloadBuilder() {}

    public static String buildPolicyIssued(String policyId, String policyNumber,
                                           String partnerId, String productId,
                                           LocalDate coverageStartDate, BigDecimal premium) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyIssued\",\"policyId\":\"%s\"," +
                "\"policyNumber\":\"%s\",\"partnerId\":\"%s\",\"productId\":\"%s\"," +
                "\"coverageStartDate\":\"%s\",\"premium\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNumber, partnerId, productId,
                coverageStartDate, premium.toPlainString(), Instant.now());
    }

    public static String buildPolicyCancelled(String policyId, String policyNumber) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyCancelled\",\"policyId\":\"%s\"," +
                "\"policyNumber\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNumber, Instant.now());
    }

    public static String buildPolicyChanged(String policyId, String policyNumber,
                                            BigDecimal premium, BigDecimal deductible) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyChanged\",\"policyId\":\"%s\"," +
                "\"policyNumber\":\"%s\",\"premium\":\"%s\",\"deductible\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNumber,
                premium.toPlainString(), deductible.toPlainString(), Instant.now());
    }

    public static String buildCoverageAdded(String policyId, String coverageId,
                                            CoverageType coverageType, BigDecimal insuredAmount) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"CoverageAdded\",\"policyId\":\"%s\"," +
                "\"coverageId\":\"%s\",\"coverageType\":\"%s\",\"insuredAmount\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, coverageId, coverageType.name(),
                insuredAmount.toPlainString(), Instant.now());
    }

    public static String buildCoverageRemoved(String policyId, String coverageId) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"CoverageRemoved\",\"policyId\":\"%s\"," +
                "\"coverageId\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, coverageId, Instant.now());
    }
}
