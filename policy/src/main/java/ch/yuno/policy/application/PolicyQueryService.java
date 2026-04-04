package ch.yuno.policy.application;

import ch.yuno.policy.domain.model.PageRequest;
import ch.yuno.policy.domain.model.PageResult;
import ch.yuno.policy.domain.model.PartnerView;
import ch.yuno.policy.domain.model.Policy;
import ch.yuno.policy.domain.model.PolicyId;
import ch.yuno.policy.domain.model.PolicyStatus;
import ch.yuno.policy.domain.model.ProductView;
import ch.yuno.policy.domain.port.in.PolicyQueryUseCase;
import ch.yuno.policy.domain.port.out.PartnerViewRepository;
import ch.yuno.policy.domain.port.out.PolicyRepository;
import ch.yuno.policy.domain.port.out.ProductViewRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class PolicyQueryService implements PolicyQueryUseCase {

    @Inject
    PolicyRepository policyRepository;

    @Inject
    PartnerViewRepository partnerViewRepository;

    @Inject
    ProductViewRepository productViewRepository;

    public Policy findById(PolicyId policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException(policyId.value()));
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

    public PageResult<Policy> searchPolicies(String policyNumber, String partnerId, String statusStr, PageRequest pageRequest) {
        PolicyStatus status = (statusStr != null && !statusStr.isBlank())
                ? PolicyStatus.valueOf(statusStr) : null;
        return policyRepository.search(policyNumber, partnerId, status, pageRequest);
    }

    // ── Read Model Queries (Partner & Product) ─────────────────────────────────

    public Map<String, PartnerView> getPartnerViewsMap() {
        Map<String, PartnerView> map = new HashMap<>();
        partnerViewRepository.findAll().forEach(p -> map.put(p.getPartnerId(), p));
        return map;
    }

    public Optional<PartnerView> findPartnerView(String partnerId) {
        return partnerViewRepository.findById(partnerId);
    }

    /**
     * Searches partner views by name fragment (case-insensitive, max 20 results).
     * Returns top 20 partners when query is blank.
     */
    public List<PartnerView> searchPartnerViews(String nameQuery) {
        return partnerViewRepository.search(nameQuery);
    }

    public List<ProductView> getActiveProducts() {
        return productViewRepository.findAllActive();
    }

    public Map<String, ProductView> getProductViewsMap() {
        Map<String, ProductView> map = new HashMap<>();
        productViewRepository.findAll().forEach(p -> map.put(p.getProductId(), p));
        return map;
    }
}
