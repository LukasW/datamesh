package ch.yuno.policy.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_sicht")
public class PartnerViewEntity {
    @Id
    @Column(name = "partner_id", length = 36)
    private String partnerId;
    @Column(name = "name", nullable = false, length = 255)
    private String name;
    @Column(name = "insured_number", length = 11)
    private String insuredNumber;
    @Column(name = "upserted_at", nullable = false)
    private LocalDateTime upsertedAt;
    @PrePersist
    @PreUpdate
    protected void onUpsert() { upsertedAt = LocalDateTime.now(); }
    public String getPartnerId() { return partnerId; }
    public void setPartnerId(String partnerId) { this.partnerId = partnerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInsuredNumber() { return insuredNumber; }
    public void setInsuredNumber(String insuredNumber) { this.insuredNumber = insuredNumber; }
    public LocalDateTime getUpsertedAt() { return upsertedAt; }
}
