package ch.yuno.claims.infrastructure.messaging;

import ch.yuno.claims.domain.model.Claim;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimsEventPayloadBuilderTest {

    @Test
    void buildClaimUnderReviewContainsExpectedFields() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.of(2026, 4, 18));
        claim.startReview();

        String json = ClaimsEventPayloadBuilder.buildClaimUnderReview(claim);

        assertTrue(json.contains("\"eventType\":\"ClaimUnderReview\""));
        assertTrue(json.contains("\"status\":\"IN_REVIEW\""));
        assertTrue(json.contains("\"claimId\":\"" + claim.getClaimId() + "\""));
        assertTrue(json.contains("\"claimNumber\":\"" + claim.getClaimNumber() + "\""));
        assertTrue(json.contains("\"policyId\":\"policy-1\""));
        assertTrue(json.contains("\"claimDate\":\"2026-04-18\""));
    }

    @Test
    void buildClaimSettledContainsSettlementAmountField() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.of(2026, 4, 18));
        claim.startReview();
        claim.settle();

        String json = ClaimsEventPayloadBuilder.buildClaimSettled(claim, new BigDecimal("1500.00"));

        assertTrue(json.contains("\"eventType\":\"ClaimSettled\""));
        assertTrue(json.contains("\"status\":\"SETTLED\""));
        assertTrue(json.contains("\"settlementAmount\":1500.00"));
    }

    @Test
    void buildClaimRejectedContainsExpectedFields() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.of(2026, 4, 18));
        claim.reject();

        String json = ClaimsEventPayloadBuilder.buildClaimRejected(claim);

        assertTrue(json.contains("\"eventType\":\"ClaimRejected\""));
        assertTrue(json.contains("\"status\":\"REJECTED\""));
        assertTrue(json.contains("\"claimId\":\"" + claim.getClaimId() + "\""));
        assertTrue(json.contains("\"policyId\":\"policy-1\""));
    }
}
