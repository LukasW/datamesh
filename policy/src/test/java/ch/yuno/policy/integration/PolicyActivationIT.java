package ch.yuno.policy.integration;

import ch.yuno.policy.domain.model.CoverageId;
import ch.yuno.policy.domain.model.CoverageType;
import ch.yuno.policy.domain.model.PolicyId;
import ch.yuno.policy.domain.service.PolicyCommandService;
import ch.yuno.policy.domain.service.PolicyQueryService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("Policy - Activation Integration Test")
class PolicyActivationIT {

    @Inject
    PolicyCommandService commandService;

    @Inject
    PolicyQueryService queryService;

    @Inject
    EntityManager em;

    @Test
    @DisplayName("Creating and activating a policy writes PolicyIssued outbox entry")
    void activatePolicy_writesOutboxEntry() {
        // Create a DRAFT policy
        PolicyId policyId = commandService.createPolicy(
                "partner-123", "product-456",
                LocalDate.now(), LocalDate.now().plusYears(1),
                new BigDecimal("500.00"), new BigDecimal("200.00"));

        assertNotNull(policyId);

        // Activate the policy (DRAFT -> ACTIVE)
        commandService.activatePolicy(policyId);

        // Verify policy is now ACTIVE
        var policy = queryService.findById(policyId);
        assertEquals("ACTIVE", policy.getStatus().name());

        // Verify outbox entry for PolicyIssued was written
        var outboxEntries = em.createNativeQuery(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = :id AND event_type = 'PolicyIssued'")
            .setParameter("id", policyId.value())
            .getSingleResult();
        assertEquals(1L, ((Number) outboxEntries).longValue(),
            "Exactly one PolicyIssued outbox entry should exist");
    }

    @Test
    @DisplayName("Adding coverages to a policy persists them correctly")
    void addCoverage_persistsCorrectly() {
        PolicyId policyId = commandService.createPolicy(
                "partner-789", "product-abc",
                LocalDate.now(), null,
                new BigDecimal("300.00"), BigDecimal.ZERO);

        CoverageId coverageId = commandService.addCoverage(
                policyId, CoverageType.LIABILITY, new BigDecimal("100000.00"));

        assertNotNull(coverageId);

        // Verify coverage was persisted
        var policy = queryService.findById(policyId);
        assertEquals(1, policy.getCoverages().size());
        assertEquals(CoverageType.LIABILITY, policy.getCoverages().get(0).getCoverageType());
    }

    @Test
    @DisplayName("Cancelling a policy writes PolicyCancelled outbox entry")
    void cancelPolicy_writesOutboxEntry() {
        PolicyId policyId = commandService.createPolicy(
                "partner-cancel", "product-cancel",
                LocalDate.now(), LocalDate.now().plusYears(1),
                new BigDecimal("400.00"), new BigDecimal("100.00"));

        commandService.activatePolicy(policyId);
        commandService.cancelPolicy(policyId);

        // Verify policy is now CANCELLED
        var policy = queryService.findById(policyId);
        assertEquals("CANCELLED", policy.getStatus().name());

        // Verify outbox entry for PolicyCancelled was written
        var outboxEntries = em.createNativeQuery(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = :id AND event_type = 'PolicyCancelled'")
            .setParameter("id", policyId.value())
            .getSingleResult();
        assertEquals(1L, ((Number) outboxEntries).longValue(),
            "Exactly one PolicyCancelled outbox entry should exist");
    }
}
