package ch.yuno.claims.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ClaimTest {

    @Test
    void openNewClaimHasOpenStatus() {
        Claim claim = Claim.openNew("policy-1", "Water damage in living room", LocalDate.of(2026, 3, 1));
        assertEquals(ClaimStatus.OPEN, claim.getStatus());
    }

    @Test
    void openNewClaimGeneratesClaimNumber() {
        Claim claim = Claim.openNew("policy-1", "Fire damage", LocalDate.of(2026, 3, 1));
        assertNotNull(claim.getClaimNumber());
        assertTrue(claim.getClaimNumber().startsWith("CLM-"));
    }

    @Test
    void openNewWithBlankPolicyIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Claim.openNew("  ", "desc", LocalDate.now()));
    }

    @Test
    void openNewWithNullDescriptionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Claim.openNew("policy-1", null, LocalDate.now()));
    }

    @Test
    void startReviewTransitionsToInReview() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.startReview();
        assertEquals(ClaimStatus.IN_REVIEW, claim.getStatus());
    }

    @Test
    void startReviewOnNonOpenClaimThrows() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.startReview();
        assertThrows(IllegalStateException.class, claim::startReview);
    }

    @Test
    void settleTransitionsToSettled() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.startReview();
        claim.settle();
        assertEquals(ClaimStatus.SETTLED, claim.getStatus());
    }

    @Test
    void settleWithoutReviewThrows() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        assertThrows(IllegalStateException.class, claim::settle);
    }

    @Test
    void rejectFromOpenTransitionsToRejected() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.reject();
        assertEquals(ClaimStatus.REJECTED, claim.getStatus());
    }

    @Test
    void rejectFromInReviewTransitionsToRejected() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.startReview();
        claim.reject();
        assertEquals(ClaimStatus.REJECTED, claim.getStatus());
    }

    @Test
    void rejectFromSettledThrows() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.startReview();
        claim.settle();
        assertThrows(IllegalStateException.class, claim::reject);
    }
}
