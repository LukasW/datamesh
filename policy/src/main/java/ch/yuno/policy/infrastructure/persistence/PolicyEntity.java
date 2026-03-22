package ch.yuno.policy.infrastructure.persistence;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "policy")
@Audited
public class PolicyEntity {

    @Id
    @Column(name = "policy_id", length = 36)
    private String policyId;

    @Column(name = "policy_number", nullable = false, unique = true, length = 50)
    private String policyNumber;

    @Column(name = "partner_id", nullable = false, length = 36)
    private String partnerId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "coverage_start_date", nullable = false)
    private LocalDate coverageStartDate;

    @Column(name = "coverage_end_date")
    private LocalDate coverageEndDate;

    @Column(name = "premium", nullable = false, precision = 12, scale = 2)
    private BigDecimal premium;

    @Column(name = "deductible", nullable = false, precision = 12, scale = 2)
    private BigDecimal deductible;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Audited
    private List<CoverageEntity> coverages = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters & Setters
    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public String getPartnerId() { return partnerId; }
    public void setPartnerId(String partnerId) { this.partnerId = partnerId; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getCoverageStartDate() { return coverageStartDate; }
    public void setCoverageStartDate(LocalDate coverageStartDate) { this.coverageStartDate = coverageStartDate; }
    public LocalDate getCoverageEndDate() { return coverageEndDate; }
    public void setCoverageEndDate(LocalDate coverageEndDate) { this.coverageEndDate = coverageEndDate; }
    public BigDecimal getPremium() { return premium; }
    public void setPremium(BigDecimal premium) { this.premium = premium; }
    public BigDecimal getDeductible() { return deductible; }
    public void setDeductible(BigDecimal deductible) { this.deductible = deductible; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<CoverageEntity> getCoverages() { return coverages; }
    public void setCoverages(List<CoverageEntity> coverages) { this.coverages = coverages; }
}
