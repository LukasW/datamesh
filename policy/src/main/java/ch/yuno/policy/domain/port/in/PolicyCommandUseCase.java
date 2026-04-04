package ch.yuno.policy.domain.port.in;

import ch.yuno.policy.domain.model.CoverageId;
import ch.yuno.policy.domain.model.CoverageType;
import ch.yuno.policy.domain.model.PolicyId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Inbound port for policy command use cases.
 */
public interface PolicyCommandUseCase {

    PolicyId createPolicy(String partnerId, String productId,
                          LocalDate coverageStartDate, LocalDate coverageEndDate,
                          BigDecimal premium, BigDecimal deductible);

    PolicyId createPolicyWithPremiumCalculation(String partnerId, String productId,
                                                String productLine, int partnerAge,
                                                String postalCode,
                                                LocalDate coverageStartDate,
                                                LocalDate coverageEndDate,
                                                BigDecimal deductible,
                                                List<String> coverageTypes);

    void activatePolicy(PolicyId policyId);

    void cancelPolicy(PolicyId policyId);

    void updatePolicyDetails(PolicyId policyId, String productId,
                             LocalDate coverageStartDate, LocalDate coverageEndDate,
                             BigDecimal premium, BigDecimal deductible);

    CoverageId addCoverage(PolicyId policyId, CoverageType coverageType, BigDecimal insuredAmount);

    void removeCoverage(PolicyId policyId, CoverageId coverageId);
}
