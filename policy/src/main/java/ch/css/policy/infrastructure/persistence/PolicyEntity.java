package ch.css.policy.infrastructure.persistence;

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

    @Column(name = "policy_nummer", nullable = false, unique = true, length = 50)
    private String policyNummer;

    @Column(name = "partner_id", nullable = false, length = 36)
    private String partnerId;

    @Column(name = "produkt_id", nullable = false, length = 36)
    private String produktId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "versicherungsbeginn", nullable = false)
    private LocalDate versicherungsbeginn;

    @Column(name = "versicherungsende")
    private LocalDate versicherungsende;

    @Column(name = "praemie", nullable = false, precision = 12, scale = 2)
    private BigDecimal praemie;

    @Column(name = "selbstbehalt", nullable = false, precision = 12, scale = 2)
    private BigDecimal selbstbehalt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Audited
    private List<DeckungEntity> deckungen = new ArrayList<>();

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
    public String getPolicyNummer() { return policyNummer; }
    public void setPolicyNummer(String policyNummer) { this.policyNummer = policyNummer; }
    public String getPartnerId() { return partnerId; }
    public void setPartnerId(String partnerId) { this.partnerId = partnerId; }
    public String getProduktId() { return produktId; }
    public void setProduktId(String produktId) { this.produktId = produktId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getVersicherungsbeginn() { return versicherungsbeginn; }
    public void setVersicherungsbeginn(LocalDate versicherungsbeginn) { this.versicherungsbeginn = versicherungsbeginn; }
    public LocalDate getVersicherungsende() { return versicherungsende; }
    public void setVersicherungsende(LocalDate versicherungsende) { this.versicherungsende = versicherungsende; }
    public BigDecimal getPraemie() { return praemie; }
    public void setPraemie(BigDecimal praemie) { this.praemie = praemie; }
    public BigDecimal getSelbstbehalt() { return selbstbehalt; }
    public void setSelbstbehalt(BigDecimal selbstbehalt) { this.selbstbehalt = selbstbehalt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<DeckungEntity> getDeckungen() { return deckungen; }
    public void setDeckungen(List<DeckungEntity> deckungen) { this.deckungen = deckungen; }
}

