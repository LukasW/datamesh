package ch.css.policy.domain.model;

import java.math.BigDecimal;

/**
 * Entity: Coverage – owned by Policy.
 * Represents one coverage type within a policy.
 */
public class Coverage {

    private final String coverageId;
    private final String policyId;
    private final CoverageType coverageType;
    private BigDecimal insuredAmount;

    public Coverage(String coverageId, String policyId, CoverageType coverageType,
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

    public String getCoverageId() { return coverageId; }
    public String getPolicyId() { return policyId; }
    public CoverageType getCoverageType() { return coverageType; }
    public BigDecimal getInsuredAmount() { return insuredAmount; }
}
