package ch.yuno.policy.domain.port.in;

import ch.yuno.policy.domain.model.PageRequest;
import ch.yuno.policy.domain.model.PageResult;
import ch.yuno.policy.domain.model.PartnerView;
import ch.yuno.policy.domain.model.Policy;
import ch.yuno.policy.domain.model.PolicyId;
import ch.yuno.policy.domain.model.ProductView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inbound port for policy query use cases.
 */
public interface PolicyQueryUseCase {

    Policy findById(PolicyId policyId);

    Policy findByPolicyNumber(String policyNumber);

    List<Policy> listAllPolicies();

    List<Policy> searchPolicies(String policyNumber, String partnerId, String statusStr);

    PageResult<Policy> searchPolicies(String policyNumber, String partnerId, String statusStr, PageRequest pageRequest);

    Map<String, PartnerView> getPartnerViewsMap();

    Optional<PartnerView> findPartnerView(String partnerId);

    List<PartnerView> searchPartnerViews(String nameQuery);

    List<ProductView> getActiveProducts();

    Map<String, ProductView> getProductViewsMap();
}
