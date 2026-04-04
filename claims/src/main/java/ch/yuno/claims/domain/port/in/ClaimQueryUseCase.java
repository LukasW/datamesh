package ch.yuno.claims.domain.port.in;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.model.ClaimId;
import ch.yuno.claims.domain.model.PartnerSearchView;
import ch.yuno.claims.domain.model.PolicySnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Inbound port for claim query use cases.
 */
public interface ClaimQueryUseCase {

    Claim findById(ClaimId claimId);

    List<Claim> findByPolicyId(String policyId);

    List<PartnerSearchView> searchPartners(String nameQuery);

    Optional<PartnerSearchView> findPartnerBySocialSecurityNumber(String ssn);

    Optional<PartnerSearchView> findPartnerByInsuredNumber(String insuredNumber);

    List<PolicySnapshot> findPoliciesForPartner(String partnerId);

    Optional<PartnerSearchView> findPartnerById(String partnerId);
}
