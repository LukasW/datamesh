package ch.yuno.claims.infrastructure.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "policy_snapshot")
public class PolicySnapshotEntity {

    @Id
    @Column(name = "policy_id", length = 36, nullable = false)
    private String policyId;

    @Column(name = "policy_number", nullable = false, length = 50)
    private String policyNumber;

    @Column(name = "partner_id", nullable = false, length = 36)
    private String partnerId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "coverage_start_date", nullable = false)
    private LocalDate coverageStartDate;

    @Column(name = "premium", nullable = false, precision = 12, scale = 2)
    private BigDecimal premium;

    @Column(name = "upserted_at", nullable = false)
    private LocalDateTime upsertedAt;

    @PrePersist
    @PreUpdate
    protected void onUpsert() {
        upsertedAt = LocalDateTime.now();
    }

    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }

    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }

    public String getPartnerId() { return partnerId; }
    public void setPartnerId(String partnerId) { this.partnerId = partnerId; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public LocalDate getCoverageStartDate() { return coverageStartDate; }
    public void setCoverageStartDate(LocalDate coverageStartDate) { this.coverageStartDate = coverageStartDate; }

    public BigDecimal getPremium() { return premium; }
    public void setPremium(BigDecimal premium) { this.premium = premium; }
}
