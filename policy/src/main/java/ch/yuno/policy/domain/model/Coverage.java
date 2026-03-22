package ch.yuno.policy.domain.model;

import java.math.BigDecimal;

/**
 * Entity: Coverage – owned by Policy.
 * Represents one coverage type within a policy.
 */
public class Coverage {

    private final CoverageId coverageId;
    private final PolicyId policyId;
    private final CoverageType coverageType;
    private BigDecimal insuredAmount;

    public Coverage(CoverageId coverageId, PolicyId policyId, CoverageType coverageType,
                    BigDecimal insuredAmount) {
        if (coverageId == null) throw new IllegalArgumentException("coverageId is required");
        if (policyId == null) throw new IllegalArgumentException("policyId is required");
        if (coverageType == null) throw new IllegalArgumentException("coverageType is required");
        if (insuredAmount == null || insuredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("insuredAmount must be greater than 0");
        }
        this.coverageId = coverageId;
        this.policyId = policyId;
        this.coverageType = coverageType;
        this.insuredAmount = insuredAmount;
    }

    public void updateInsuredAmount(BigDecimal insuredAmount) {
        if (insuredAmount == null || insuredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("insuredAmount must be greater than 0");
        }
        this.insuredAmount = insuredAmount;
    }

    public CoverageId getCoverageId() { return coverageId; }
    public PolicyId getPolicyId() { return policyId; }
    public CoverageType getCoverageType() { return coverageType; }
    public BigDecimal getInsuredAmount() { return insuredAmount; }
}
