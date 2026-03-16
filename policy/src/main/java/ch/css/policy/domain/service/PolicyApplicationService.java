package ch.css.policy.domain.service;

import ch.css.policy.domain.model.CoverageType;
import ch.css.policy.domain.model.PartnerView;
import ch.css.policy.domain.model.Policy;
import ch.css.policy.domain.model.PolicyStatus;
import ch.css.policy.domain.model.ProductView;
import ch.css.policy.domain.port.out.PartnerViewRepository;
import ch.css.policy.domain.port.out.PolicyEventPublisher;
import ch.css.policy.domain.port.out.PolicyRepository;
import ch.css.policy.domain.port.out.ProductViewRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class PolicyApplicationService {

    @Inject
    PolicyRepository policyRepository;

    @Inject
    PolicyEventPublisher policyEventPublisher;

    @Inject
    PartnerViewRepository partnerViewRepository;

    @Inject
    ProductViewRepository productViewRepository;

    // ── Policy Management ──────────────────────────────────────────────────────

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
        policyEventPublisher.publishPolicyIssued(
                policy.getPolicyId(), policy.getPolicyNumber(),
                policy.getPartnerId(), policy.getProductId(),
                policy.getCoverageStartDate(), policy.getPremium());
    }

    @Transactional
    public void cancelPolicy(String policyId) {
        Policy policy = findOrThrow(policyId);
        policy.cancel();
        policyRepository.save(policy);
        policyEventPublisher.publishPolicyCancelled(policy.getPolicyId(), policy.getPolicyNumber());
    }

    @Transactional
    public void updatePolicyDetails(String policyId, String productId,
                                    LocalDate coverageStartDate, LocalDate coverageEndDate,
                                    BigDecimal premium, BigDecimal deductible) {
        Policy policy = findOrThrow(policyId);
        policy.updateDetails(productId, coverageStartDate, coverageEndDate, premium, deductible);
        policyRepository.save(policy);
        policyEventPublisher.publishPolicyChanged(
                policy.getPolicyId(), policy.getPolicyNumber(),
                policy.getPremium(), policy.getDeductible());
    }

    @Transactional
    public String addCoverage(String policyId, CoverageType coverageType, BigDecimal insuredAmount) {
        Policy policy = findOrThrow(policyId);
        String coverageId = policy.addCoverage(coverageType, insuredAmount);
        policyRepository.save(policy);
        policyEventPublisher.publishCoverageAdded(policyId, coverageId, coverageType, insuredAmount);
        return coverageId;
    }

    @Transactional
    public void removeCoverage(String policyId, String coverageId) {
        Policy policy = findOrThrow(policyId);
        policy.removeCoverage(coverageId);
        policyRepository.save(policy);
        policyEventPublisher.publishCoverageRemoved(policyId, coverageId);
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    public Policy findById(String policyId) {
        return findOrThrow(policyId);
    }

    public Policy findByPolicyNumber(String policyNumber) {
        return policyRepository.findByPolicyNumber(policyNumber)
                .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
    }

    public List<Policy> listAllPolicies() {
        return policyRepository.search(null, null, null);
    }

    public List<Policy> searchPolicies(String policyNumber, String partnerId, String statusStr) {
        PolicyStatus status = (statusStr != null && !statusStr.isBlank())
                ? PolicyStatus.valueOf(statusStr) : null;
        return policyRepository.search(policyNumber, partnerId, status);
    }

    // ── Read Model Queries (Partner & Product) ─────────────────────────────────

    public java.util.Map<String, PartnerView> getPartnerViewsMap() {
        java.util.Map<String, PartnerView> map = new java.util.HashMap<>();
        partnerViewRepository.findAll().forEach(p -> map.put(p.getPartnerId(), p));
        return map;
    }

    public java.util.Optional<PartnerView> findPartnerView(String partnerId) {
        return partnerViewRepository.findById(partnerId);
    }

    /**
     * Searches partner views by name fragment (case-insensitive, max 20 results).
     * Returns top 20 partners when query is blank.
     */
    public java.util.List<PartnerView> searchPartnerViews(String nameQuery) {
        return partnerViewRepository.search(nameQuery);
    }

    public java.util.List<ProductView> getActiveProducts() {
        return productViewRepository.findAllActive();
    }

    public java.util.Map<String, ProductView> getProductViewsMap() {
        java.util.Map<String, ProductView> map = new java.util.HashMap<>();
        productViewRepository.findAll().forEach(p -> map.put(p.getProductId(), p));
        return map;
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
