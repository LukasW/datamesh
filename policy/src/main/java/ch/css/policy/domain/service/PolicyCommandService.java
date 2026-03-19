package ch.css.policy.domain.service;

import ch.css.policy.domain.model.CoverageType;
import ch.css.policy.domain.model.Policy;
import ch.css.policy.domain.port.out.OutboxRepository;
import ch.css.policy.domain.port.out.PolicyRepository;
import ch.css.policy.infrastructure.messaging.PolicyEventPayloadBuilder;
import ch.css.policy.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class PolicyCommandService {

    @Inject
    PolicyRepository policyRepository;

    @Inject
    OutboxRepository outboxRepository;

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
