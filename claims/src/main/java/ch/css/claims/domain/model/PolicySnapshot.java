package ch.css.claims.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read model – materialized view of a Policy, built from policy.v1.issued events.
 * Used by the Claims domain for FNOL coverage checks (ADR-008).
 * No REST call to the Policy service is needed; this snapshot is stored locally.
 */
public record PolicySnapshot(
        String policyId,
        String policyNumber,
        String partnerId,
        String productId,
        LocalDate coverageStartDate,
        BigDecimal premium
) {
    public PolicySnapshot {
        if (policyId == null || policyId.isBlank()) throw new IllegalArgumentException("policyId required");
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId required");
    }
}
