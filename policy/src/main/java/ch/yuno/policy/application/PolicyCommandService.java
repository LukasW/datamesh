package ch.yuno.policy.application;

import ch.yuno.policy.domain.model.CoverageId;
import ch.yuno.policy.domain.model.CoverageType;
import ch.yuno.policy.domain.model.Policy;
import ch.yuno.policy.domain.model.PolicyId;
import ch.yuno.policy.domain.model.PremiumCalculationResult;
import ch.yuno.policy.domain.port.in.PolicyCommandUseCase;
import ch.yuno.policy.domain.port.out.PolicyEventPublisher;
import ch.yuno.policy.domain.port.out.PolicyRepository;
import ch.yuno.policy.domain.port.out.PremiumCalculationPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class PolicyCommandService implements PolicyCommandUseCase {

    @Inject
    PolicyRepository policyRepository;

    @Inject
    PolicyEventPublisher policyEventPublisher;

    @Inject
    PremiumCalculationPort premiumCalculationPort;

    /**
     * Creates a new policy with the given premium.
     */
    @Transactional
    public PolicyId createPolicy(String partnerId, String productId,
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
    public PolicyId createPolicyWithPremiumCalculation(String partnerId, String productId,
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
    public void activatePolicy(PolicyId policyId) {
        Policy policy = findOrThrow(policyId);
        policy.activate();
        policyRepository.save(policy);
        policyEventPublisher.policyIssued(policy);
    }

    @Transactional
    public void cancelPolicy(PolicyId policyId) {
        Policy policy = findOrThrow(policyId);
        policy.cancel();
        policyRepository.save(policy);
        policyEventPublisher.policyCancelled(policy);
    }

    @Transactional
    public void updatePolicyDetails(PolicyId policyId, String productId,
                                    LocalDate coverageStartDate, LocalDate coverageEndDate,
                                    BigDecimal premium, BigDecimal deductible) {
        Policy policy = findOrThrow(policyId);
        policy.updateDetails(productId, coverageStartDate, coverageEndDate, premium, deductible);
        policyRepository.save(policy);
        policyEventPublisher.policyChanged(policy);
    }

    @Transactional
    public CoverageId addCoverage(PolicyId policyId, CoverageType coverageType, BigDecimal insuredAmount) {
        Policy policy = findOrThrow(policyId);
        CoverageId coverageId = policy.addCoverage(coverageType, insuredAmount);
        policyRepository.save(policy);
        policyEventPublisher.coverageAdded(policyId, coverageId, coverageType, insuredAmount);
        return coverageId;
    }

    @Transactional
    public void removeCoverage(PolicyId policyId, CoverageId coverageId) {
        Policy policy = findOrThrow(policyId);
        policy.removeCoverage(coverageId);
        policyRepository.save(policy);
        policyEventPublisher.coverageRemoved(policyId, coverageId);
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private Policy findOrThrow(PolicyId policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException(policyId.value()));
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
