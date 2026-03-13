package ch.css.policy.domain.model;

import ch.css.policy.domain.service.DeckungNotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root: Policy (insurance contract).
 * All state changes go through this aggregate. Invariants are enforced here.
 */
public class Policy {

    private final String policyId;
    private final String policyNummer;
    private final String partnerId;
    private String produktId;
    private PolicyStatus status;
    private LocalDate versicherungsbeginn;
    private LocalDate versicherungsende; // null = unbefristet
    private BigDecimal praemie;
    private BigDecimal selbstbehalt;
    private List<Deckung> deckungen;

    /** Constructor for creating a new Policy (status = ENTWURF). */
    public Policy(String policyNummer, String partnerId, String produktId,
                  LocalDate versicherungsbeginn, LocalDate versicherungsende,
                  BigDecimal praemie, BigDecimal selbstbehalt) {
        validate(policyNummer, partnerId, produktId, versicherungsbeginn, praemie, selbstbehalt);
        if (versicherungsende != null && versicherungsbeginn.isAfter(versicherungsende)) {
            throw new IllegalArgumentException("versicherungsbeginn darf nicht nach versicherungsende liegen");
        }
        this.policyId = UUID.randomUUID().toString();
        this.policyNummer = policyNummer;
        this.partnerId = partnerId;
        this.produktId = produktId;
        this.status = PolicyStatus.ENTWURF;
        this.versicherungsbeginn = versicherungsbeginn;
        this.versicherungsende = versicherungsende;
        this.praemie = praemie;
        this.selbstbehalt = selbstbehalt;
        this.deckungen = new ArrayList<>();
    }

    /** Constructor for reconstructing from persistence. */
    public Policy(String policyId, String policyNummer, String partnerId, String produktId,
                  PolicyStatus status, LocalDate versicherungsbeginn, LocalDate versicherungsende,
                  BigDecimal praemie, BigDecimal selbstbehalt) {
        this.policyId = policyId;
        this.policyNummer = policyNummer;
        this.partnerId = partnerId;
        this.produktId = produktId;
        this.status = status;
        this.versicherungsbeginn = versicherungsbeginn;
        this.versicherungsende = versicherungsende;
        this.praemie = praemie;
        this.selbstbehalt = selbstbehalt;
        this.deckungen = new ArrayList<>();
    }

    // ── Business Methods ──────────────────────────────────────────────────────

    /** Activates the policy (ENTWURF → AKTIV). */
    public void aktivieren() {
        if (status != PolicyStatus.ENTWURF) {
            throw new IllegalStateException("Nur Entwürfe können aktiviert werden (aktueller Status: " + status + ")");
        }
        this.status = PolicyStatus.AKTIV;
    }

    /** Cancels the policy (AKTIV → GEKUENDIGT). */
    public void kuendigen() {
        if (status != PolicyStatus.AKTIV) {
            throw new IllegalStateException("Nur aktive Policen können gekündigt werden (aktueller Status: " + status + ")");
        }
        this.status = PolicyStatus.GEKUENDIGT;
    }

    /** Updates the policy details. Only allowed in ENTWURF or AKTIV status. */
    public void updateDetails(String produktId, LocalDate versicherungsbeginn,
                               LocalDate versicherungsende, BigDecimal praemie, BigDecimal selbstbehalt) {
        if (status == PolicyStatus.GEKUENDIGT || status == PolicyStatus.ABGELAUFEN) {
            throw new IllegalStateException("Gekündigte oder abgelaufene Policen können nicht geändert werden");
        }
        if (produktId == null || produktId.isBlank()) throw new IllegalArgumentException("produktId ist Pflichtfeld");
        if (versicherungsbeginn == null) throw new IllegalArgumentException("versicherungsbeginn ist Pflichtfeld");
        if (praemie == null || praemie.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("praemie muss grösser als 0 sein");
        if (selbstbehalt == null || selbstbehalt.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("selbstbehalt darf nicht negativ sein");
        if (versicherungsende != null && versicherungsbeginn.isAfter(versicherungsende)) {
            throw new IllegalArgumentException("versicherungsbeginn darf nicht nach versicherungsende liegen");
        }
        this.produktId = produktId;
        this.versicherungsbeginn = versicherungsbeginn;
        this.versicherungsende = versicherungsende;
        this.praemie = praemie;
        this.selbstbehalt = selbstbehalt;
    }

    /**
     * Adds a coverage to the policy.
     * Each Deckungstyp may only appear once per policy.
     * @return the new deckungId
     */
    public String addDeckung(Deckungstyp deckungstyp, BigDecimal versicherungssumme) {
        if (status == PolicyStatus.GEKUENDIGT || status == PolicyStatus.ABGELAUFEN) {
            throw new IllegalStateException("Deckungen können bei dieser Police nicht mehr hinzugefügt werden");
        }
        boolean exists = deckungen.stream().anyMatch(d -> d.getDeckungstyp() == deckungstyp);
        if (exists) {
            throw new IllegalArgumentException("Deckungstyp " + deckungstyp + " ist bereits vorhanden");
        }
        String deckungId = UUID.randomUUID().toString();
        deckungen.add(new Deckung(deckungId, policyId, deckungstyp, versicherungssumme));
        return deckungId;
    }

    /** Removes a coverage by ID. */
    public void removeDeckung(String deckungId) {
        findDeckung(deckungId); // throws DeckungNotFoundException if not found
        deckungen.removeIf(d -> d.getDeckungId().equals(deckungId));
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Deckung findDeckung(String deckungId) {
        return deckungen.stream()
                .filter(d -> d.getDeckungId().equals(deckungId))
                .findFirst()
                .orElseThrow(() -> new DeckungNotFoundException(deckungId));
    }

    private static void validate(String policyNummer, String partnerId, String produktId,
                                  LocalDate versicherungsbeginn, BigDecimal praemie, BigDecimal selbstbehalt) {
        if (policyNummer == null || policyNummer.isBlank()) throw new IllegalArgumentException("policyNummer ist Pflichtfeld");
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId ist Pflichtfeld");
        if (produktId == null || produktId.isBlank()) throw new IllegalArgumentException("produktId ist Pflichtfeld");
        if (versicherungsbeginn == null) throw new IllegalArgumentException("versicherungsbeginn ist Pflichtfeld");
        if (praemie == null || praemie.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("praemie muss grösser als 0 sein");
        if (selbstbehalt == null || selbstbehalt.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("selbstbehalt darf nicht negativ sein");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getPolicyId() { return policyId; }
    public String getPolicyNummer() { return policyNummer; }
    public String getPartnerId() { return partnerId; }
    public String getProduktId() { return produktId; }
    public PolicyStatus getStatus() { return status; }
    public LocalDate getVersicherungsbeginn() { return versicherungsbeginn; }
    public LocalDate getVersicherungsende() { return versicherungsende; }
    public BigDecimal getPraemie() { return praemie; }
    public BigDecimal getSelbstbehalt() { return selbstbehalt; }
    public List<Deckung> getDeckungen() { return deckungen; }

    /** Used by JPA adapter to restore persisted coverages. */
    public void setDeckungen(List<Deckung> deckungen) {
        this.deckungen = deckungen != null ? new ArrayList<>(deckungen) : new ArrayList<>();
    }

    /** Used by JPA adapter to restore status after persistence. */
    public void setStatus(PolicyStatus status) {
        this.status = status;
    }
}
