package ch.css.policy.domain;

import ch.css.policy.domain.model.Coverage;
import ch.css.policy.domain.model.CoverageType;
import ch.css.policy.domain.model.Policy;
import ch.css.policy.domain.model.PolicyStatus;
import ch.css.policy.domain.service.CoverageNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Policy – Aggregate Tests")
class PolicyTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END   = LocalDate.of(2025, 12, 31);
    private static final BigDecimal PREMIUM    = new BigDecimal("1200.00");
    private static final BigDecimal DEDUCTIBLE = new BigDecimal("500.00");

    private Policy newDraft() {
        return new Policy("POL-001", "partner-1", "product-1", START, END, PREMIUM, DEDUCTIBLE);
    }

    // ── Creation ──────────────────────────────────────────────────────────────

    @Test
    void newPolicy_startsDraft() {
        Policy policy = newDraft();
        assertEquals(PolicyStatus.DRAFT, policy.getStatus());
    }

    @Test
    void newPolicy_assignsUniqueId() {
        Policy a = newDraft();
        Policy b = newDraft();
        assertNotEquals(a.getPolicyId(), b.getPolicyId());
    }

    @Test
    void newPolicy_rejectsStartAfterEnd() {
        assertThrows(IllegalArgumentException.class, () ->
                new Policy("POL-001", "p", "prod", END, START, PREMIUM, DEDUCTIBLE));
    }

    @Test
    void newPolicy_rejectsBlankPolicyNumber() {
        assertThrows(IllegalArgumentException.class, () ->
                new Policy("", "p", "prod", START, END, PREMIUM, DEDUCTIBLE));
    }

    @Test
    void newPolicy_rejectsZeroPremium() {
        assertThrows(IllegalArgumentException.class, () ->
                new Policy("POL-001", "p", "prod", START, END, BigDecimal.ZERO, DEDUCTIBLE));
    }

    @Test
    void newPolicy_rejectsNegativeDeductible() {
        assertThrows(IllegalArgumentException.class, () ->
                new Policy("POL-001", "p", "prod", START, END, PREMIUM, new BigDecimal("-1")));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    void activate_draftBecomesActive() {
        Policy policy = newDraft();
        policy.activate();
        assertEquals(PolicyStatus.ACTIVE, policy.getStatus());
    }

    @Test
    void activate_rejectsNonDraft() {
        Policy policy = newDraft();
        policy.activate();
        assertThrows(IllegalStateException.class, policy::activate);
    }

    @Test
    void cancel_activeBecomescancelled() {
        Policy policy = newDraft();
        policy.activate();
        policy.cancel();
        assertEquals(PolicyStatus.CANCELLED, policy.getStatus());
    }

    @Test
    void cancel_rejectsDraft() {
        Policy policy = newDraft();
        assertThrows(IllegalStateException.class, policy::cancel);
    }

    @Test
    void cancel_rejectsAlreadyCancelled() {
        Policy policy = newDraft();
        policy.activate();
        policy.cancel();
        assertThrows(IllegalStateException.class, policy::cancel);
    }

    // ── Coverage Management ───────────────────────────────────────────────────

    @Test
    void addCoverage_addsToList() {
        Policy policy = newDraft();
        String id = policy.addCoverage(CoverageType.LIABILITY, new BigDecimal("50000"));
        assertNotNull(id);
        assertEquals(1, policy.getCoverages().size());
        assertEquals(CoverageType.LIABILITY, policy.getCoverages().get(0).getCoverageType());
    }

    @Test
    void addCoverage_rejectsDuplicateType() {
        Policy policy = newDraft();
        policy.addCoverage(CoverageType.LIABILITY, new BigDecimal("50000"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.addCoverage(CoverageType.LIABILITY, new BigDecimal("10000")));
    }

    @Test
    void addCoverage_rejectsCancelledPolicy() {
        Policy policy = newDraft();
        policy.activate();
        policy.cancel();
        assertThrows(IllegalStateException.class, () ->
                policy.addCoverage(CoverageType.LIABILITY, new BigDecimal("50000")));
    }

    @Test
    void removeCoverage_removesByid() {
        Policy policy = newDraft();
        String id = policy.addCoverage(CoverageType.LIABILITY, new BigDecimal("50000"));
        policy.removeCoverage(id);
        assertTrue(policy.getCoverages().isEmpty());
    }

    @Test
    void removeCoverage_throwsForUnknownId() {
        Policy policy = newDraft();
        assertThrows(CoverageNotFoundException.class, () -> policy.removeCoverage("unknown-id"));
    }

    // ── Coverage Value Object ─────────────────────────────────────────────────

    @Test
    void coverage_rejectsZeroInsuredAmount() {
        assertThrows(IllegalArgumentException.class, () ->
                new Coverage("id", "policyId", CoverageType.LIABILITY, BigDecimal.ZERO));
    }

    @Test
    void coverage_updateInsuredAmount() {
        Coverage coverage = new Coverage("id", "policyId", CoverageType.LIABILITY, new BigDecimal("50000"));
        coverage.updateInsuredAmount(new BigDecimal("75000"));
        assertEquals(new BigDecimal("75000"), coverage.getInsuredAmount());
    }

    // ── Update Details ────────────────────────────────────────────────────────

    @Test
    void updateDetails_allowedOnDraft() {
        Policy policy = newDraft();
        policy.updateDetails("product-2", START, END, new BigDecimal("1500"), BigDecimal.ZERO);
        assertEquals("product-2", policy.getProductId());
        assertEquals(new BigDecimal("1500"), policy.getPremium());
    }

    @Test
    void updateDetails_allowedOnActive() {
        Policy policy = newDraft();
        policy.activate();
        assertDoesNotThrow(() ->
                policy.updateDetails("product-2", START, END, new BigDecimal("1500"), BigDecimal.ZERO));
    }

    @Test
    void updateDetails_rejectedOnCancelled() {
        Policy policy = newDraft();
        policy.activate();
        policy.cancel();
        assertThrows(IllegalStateException.class, () ->
                policy.updateDetails("product-2", START, END, PREMIUM, DEDUCTIBLE));
    }
}
