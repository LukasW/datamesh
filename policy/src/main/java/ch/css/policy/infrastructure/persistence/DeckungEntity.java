package ch.css.policy.infrastructure.persistence;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "deckung")
@Audited
public class DeckungEntity {

    @Id
    @Column(name = "deckung_id", length = 36)
    private String deckungId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private PolicyEntity policy;

    @Column(name = "deckungstyp", nullable = false, length = 30)
    private String deckungstyp;

    @Column(name = "versicherungssumme", nullable = false, precision = 15, scale = 2)
    private BigDecimal versicherungssumme;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters & Setters
    public String getDeckungId() { return deckungId; }
    public void setDeckungId(String deckungId) { this.deckungId = deckungId; }
    public PolicyEntity getPolicy() { return policy; }
    public void setPolicy(PolicyEntity policy) { this.policy = policy; }
    public String getDeckungstyp() { return deckungstyp; }
    public void setDeckungstyp(String deckungstyp) { this.deckungstyp = deckungstyp; }
    public BigDecimal getVersicherungssumme() { return versicherungssumme; }
    public void setVersicherungssumme(BigDecimal versicherungssumme) { this.versicherungssumme = versicherungssumme; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

