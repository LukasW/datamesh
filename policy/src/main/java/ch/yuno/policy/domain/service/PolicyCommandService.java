package ch.yuno.policy.domain.service;

import ch.yuno.policy.domain.model.CoverageType;
import ch.yuno.policy.domain.model.Policy;
import ch.yuno.policy.domain.model.PremiumCalculationResult;
import ch.yuno.policy.domain.port.out.OutboxRepository;
import ch.yuno.policy.domain.port.out.PolicyRepository;
import ch.yuno.policy.domain.port.out.PremiumCalculationPort;
import ch.yuno.policy.infrastructure.messaging.PolicyEventPayloadBuilder;
import ch.yuno.policy.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class PolicyCommandService {

    @Inject
    PolicyRepository policyRepository;

    @Inject
    OutboxRepository outboxRepository;

    @Inject
    PremiumCalculationPort premiumCalculationPort;

    /**
     * Creates a new policy with premium calculated by the Product Service (gRPC, ADR-010).
     *
     * @throws PremiumCalculationUnavailableException if the Product Service is unreachable
     */
    @Transactional
    public String createPolicy(String partnerId, String productId,
                               LocalDate coverageStartDate, LocalDate coverageEndDate,
                               BigDecimal premium, BigDecimal deductible) {
        String policyNumber = generatePolicyNumber();
        Policy policy = new Policy(policyNumber, partnerId, productId,
                coverageStartDate, coverageEndDate, premium, deductible);
        policyRepository.save(policy);
        return policy.getPolicyId();
    }

    /**
     * Creates a new policy with premium calculated by the Product Service (gRPC, ADR-010).
     *
     * @throws PremiumCalculationUnavailableException if the Product Service is unreachable
     */
    @Transactional
    public String createPolicyWithPremiumCalculation(String partnerId, String productId,
                                                     String productLine, int partnerAge,
                                                     String postalCode,
                                                     LocalDate coverageStartDate,
                                                     LocalDate coverageEndDate,
                                                     BigDecimal deductible,
                                                     List<String> coverageTypes) {
        // Synchronous gRPC call to Product Service for premium calculation (ADR-010)
        PremiumCalculationResult premiumResult = premiumCalculationPort.calculatePremium(
                productId, productLine, partnerAge, postalCode, coverageTypes);

        String policyNumber = generatePolicyNumber();
        Policy policy = new Policy(policyNumber, partnerId, productId,
                coverageStartDate, coverageEndDate,
                premiumResult.totalPremium(),
                deductible != null ? deductible : BigDecimal.ZERO);
        policyRepository.save(policy);
        return policy.getPolicyId();
    }

    @Transactional
    public void activatePolicy(String policyId) {
        Policy policy = findOrThrow(policyId);
        policy.activate();
        policyRepository.save(policy);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policyId, "PolicyIssued",
                PolicyEventPayloadBuilder.TOPIC_POLICY_ISSUED,
                PolicyEventPayloadBuilder.buildPolicyIssued(
                        policyId, policy.getPolicyNumber(),
                        policy.getPartnerId(), policy.getProductId(),
                        policy.getCoverageStartDate(), policy.getPremium())));
    }

    @Transactional
    public void cancelPolicy(String policyId) {
        Policy policy = findOrThrow(policyId);
        policy.cancel();
        policyRepository.save(policy);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policyId, "PolicyCancelled",
                PolicyEventPayloadBuilder.TOPIC_POLICY_CANCELLED,
                PolicyEventPayloadBuilder.buildPolicyCancelled(policyId, policy.getPolicyNumber())));
    }

    @Transactional
    public void updatePolicyDetails(String policyId, String productId,
                                    LocalDate coverageStartDate, LocalDate coverageEndDate,
                                    BigDecimal premium, BigDecimal deductible) {
        Policy policy = findOrThrow(policyId);
        policy.updateDetails(productId, coverageStartDate, coverageEndDate, premium, deductible);
        policyRepository.save(policy);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policyId, "PolicyChanged",
                PolicyEventPayloadBuilder.TOPIC_POLICY_CHANGED,
                PolicyEventPayloadBuilder.buildPolicyChanged(
                        policyId, policy.getPolicyNumber(),
                        policy.getPremium(), policy.getDeductible())));
    }

    @Transactional
    public String addCoverage(String policyId, CoverageType coverageType, BigDecimal insuredAmount) {
        Policy policy = findOrThrow(policyId);
        String coverageId = policy.addCoverage(coverageType, insuredAmount);
        policyRepository.save(policy);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policyId, "CoverageAdded",
                PolicyEventPayloadBuilder.TOPIC_COVERAGE_ADDED,
                PolicyEventPayloadBuilder.buildCoverageAdded(policyId, coverageId, coverageType, insuredAmount)));
        return coverageId;
    }

    @Transactional
    public void removeCoverage(String policyId, String coverageId) {
        Policy policy = findOrThrow(policyId);
        policy.removeCoverage(coverageId);
        policyRepository.save(policy);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policyId, "CoverageRemoved",
                PolicyEventPayloadBuilder.TOPIC_COVERAGE_REMOVED,
                PolicyEventPayloadBuilder.buildCoverageRemoved(policyId, coverageId)));
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private Policy findOrThrow(String policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException(policyId));
    }

    private String generatePolicyNumber() {
        int year = LocalDate.now().getYear();
        for (int i = 0; i < 25; i++) {
            int seq = ThreadLocalRandom.current().nextInt(1, 10_000);
            String candidate = "POL-%d-%04d".formatted(year, seq);
            if (!policyRepository.existsByPolicyNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique policy number");
    }
}
