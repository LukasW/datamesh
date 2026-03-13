package ch.css.policy.domain.model;

import java.math.BigDecimal;

/**
 * Entity: Deckung (Coverage) – owned by Policy.
 * Represents one coverage type within a policy.
 */
public class Deckung {

    private final String deckungId;
    private final String policyId;
    private final Deckungstyp deckungstyp;
    private BigDecimal versicherungssumme;

    public Deckung(String deckungId, String policyId, Deckungstyp deckungstyp,
                   BigDecimal versicherungssumme) {
        if (deckungId == null) throw new IllegalArgumentException("deckungId ist Pflichtfeld");
        if (policyId == null) throw new IllegalArgumentException("policyId ist Pflichtfeld");
        if (deckungstyp == null) throw new IllegalArgumentException("deckungstyp ist Pflichtfeld");
        if (versicherungssumme == null || versicherungssumme.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("versicherungssumme muss grösser als 0 sein");
        }
        this.deckungId = deckungId;
        this.policyId = policyId;
        this.deckungstyp = deckungstyp;
        this.versicherungssumme = versicherungssumme;
    }

    public void updateVersicherungssumme(BigDecimal versicherungssumme) {
        if (versicherungssumme == null || versicherungssumme.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("versicherungssumme muss grösser als 0 sein");
        }
        this.versicherungssumme = versicherungssumme;
    }

    public String getDeckungId() { return deckungId; }
    public String getPolicyId() { return policyId; }
    public Deckungstyp getDeckungstyp() { return deckungstyp; }
    public BigDecimal getVersicherungssumme() { return versicherungssumme; }
}
