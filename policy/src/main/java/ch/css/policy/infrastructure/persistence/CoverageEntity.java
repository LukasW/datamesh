package ch.css.policy.infrastructure.persistence;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coverage")
@Audited
public class CoverageEntity {

    @Id
    @Column(name = "coverage_id", length = 36)
    private String coverageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private PolicyEntity policy;

    @Column(name = "coverage_type", nullable = false, length = 30)
    private String coverageType;

    @Column(name = "insured_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal insuredAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters & Setters
    public String getCoverageId() { return coverageId; }
    public void setCoverageId(String coverageId) { this.coverageId = coverageId; }
    public PolicyEntity getPolicy() { return policy; }
    public void setPolicy(PolicyEntity policy) { this.policy = policy; }
    public String getCoverageType() { return coverageType; }
    public void setCoverageType(String coverageType) { this.coverageType = coverageType; }
    public BigDecimal getInsuredAmount() { return insuredAmount; }
    public void setInsuredAmount(BigDecimal insuredAmount) { this.insuredAmount = insuredAmount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
