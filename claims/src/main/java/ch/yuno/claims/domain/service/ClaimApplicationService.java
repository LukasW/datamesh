package ch.yuno.claims.domain.service;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.port.out.ClaimRepository;
import ch.yuno.claims.domain.port.out.OutboxRepository;
import ch.yuno.claims.domain.port.out.PolicySnapshotRepository;
import ch.yuno.claims.infrastructure.messaging.ClaimsEventPayloadBuilder;
import ch.yuno.claims.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Application service orchestrating claim use cases.
 * Acts as the entry point for the domain logic.
 */
@ApplicationScoped
@Transactional
public class ClaimApplicationService {

    private final ClaimRepository claimRepository;
    private final PolicySnapshotRepository policySnapshotRepository;
    private final OutboxRepository outboxRepository;

    public ClaimApplicationService(ClaimRepository claimRepository,
                                   PolicySnapshotRepository policySnapshotRepository,
                                   OutboxRepository outboxRepository) {
        this.claimRepository = claimRepository;
        this.policySnapshotRepository = policySnapshotRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Open a new claim (First Notice of Loss).
     * Coverage is checked against the local PolicySnapshot read model (ADR-008) –
     * no synchronous REST call to the Policy service is needed.
     * Publishes claims.v1.opened via the outbox.
     */
    @RolesAllowed({"CLAIMS_AGENT", "UNDERWRITER"})
    public Claim openClaim(String policyId, String description, LocalDate claimDate) {
        policySnapshotRepository.findByPolicyId(policyId)
                .orElseThrow(() -> new CoverageCheckFailedException(policyId));

        Claim claim = Claim.openNew(policyId, description, claimDate);
        claimRepository.save(claim);

        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "claims", claim.getClaimId(), "ClaimOpened",
                ClaimsEventPayloadBuilder.TOPIC_CLAIM_OPENED,
                ClaimsEventPayloadBuilder.buildClaimOpened(claim)));

        return claim;
    }

    /**
     * Start review of an open claim. Transitions OPEN → IN_REVIEW.
     */
    @RolesAllowed({"CLAIMS_AGENT"})
    public Claim startReview(String claimId) {
        Claim claim = findOrThrow(claimId);
        claim.startReview();
        claimRepository.save(claim);
        return claim;
    }

    /**
     * Settle a claim under review. Transitions IN_REVIEW → SETTLED.
     * Publishes claims.v1.settled via the outbox (triggers payout in billing).
     */
    @RolesAllowed({"CLAIMS_AGENT"})
    public Claim settle(String claimId) {
        Claim claim = findOrThrow(claimId);
        claim.settle();
        claimRepository.save(claim);

        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "claims", claim.getClaimId(), "ClaimSettled",
                ClaimsEventPayloadBuilder.TOPIC_CLAIM_SETTLED,
                ClaimsEventPayloadBuilder.buildClaimSettled(claim)));

        return claim;
    }

    /**
     * Reject a claim (OPEN or IN_REVIEW → REJECTED).
     */
    @RolesAllowed({"CLAIMS_AGENT"})
    public Claim reject(String claimId) {
        Claim claim = findOrThrow(claimId);
        claim.reject();
        claimRepository.save(claim);
        return claim;
    }

    /**
     * Find a claim by its unique identifier.
     */
    @RolesAllowed({"CLAIMS_AGENT", "UNDERWRITER"})
    public Claim findById(String claimId) {
        return findOrThrow(claimId);
    }

    /**
     * Find all claims for a given policy.
     */
    @RolesAllowed({"CLAIMS_AGENT", "UNDERWRITER"})
    public List<Claim> findByPolicyId(String policyId) {
        return claimRepository.findByPolicyId(policyId);
    }

    private Claim findOrThrow(String claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));
    }
}
