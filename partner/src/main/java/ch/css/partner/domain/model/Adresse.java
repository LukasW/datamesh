package ch.css.partner.domain.model;

import java.time.LocalDate;

/**
 * Entity: Adresse – temporal, owned by Person.
 * Invariants:
 * - gueltigVon must not be after gueltigBis.
 * - Overlap check is enforced at Aggregate level (Person).
 */
public class Adresse {

    private final String adressId;
    private final String personId;
    private final AdressTyp adressTyp;
    private String strasse;
    private String hausnummer;
    private String plz;
    private String ort;
    private String land;
    private LocalDate gueltigVon;
    private LocalDate gueltigBis; // null = unbefristet

    public Adresse(String adressId, String personId, AdressTyp adressTyp,
                   String strasse, String hausnummer, String plz, String ort, String land,
                   LocalDate gueltigVon, LocalDate gueltigBis) {
        if (adressId == null) throw new IllegalArgumentException("adressId ist Pflichtfeld");
        if (personId == null) throw new IllegalArgumentException("personId ist Pflichtfeld");
        if (adressTyp == null) throw new IllegalArgumentException("adressTyp ist Pflichtfeld");
        if (strasse == null || strasse.isBlank()) throw new IllegalArgumentException("Strasse ist Pflichtfeld");
        if (hausnummer == null || hausnummer.isBlank()) throw new IllegalArgumentException("Hausnummer ist Pflichtfeld");
        if (plz == null || !plz.matches("\\d{4}")) throw new IllegalArgumentException("PLZ muss genau 4 Ziffern enthalten (z. B. 8001): \"" + plz + "\"");
        if (ort == null || ort.isBlank()) throw new IllegalArgumentException("Ort ist Pflichtfeld");
        if (gueltigVon == null) throw new IllegalArgumentException("gueltigVon ist Pflichtfeld");
        if (gueltigBis != null && gueltigVon.isAfter(gueltigBis)) {
            throw new IllegalArgumentException("gueltigVon darf nicht nach gueltigBis liegen");
        }
        this.adressId = adressId;
        this.personId = personId;
        this.adressTyp = adressTyp;
        this.strasse = strasse;
        this.hausnummer = hausnummer;
        this.plz = plz;
        this.ort = ort;
        this.land = (land != null && !land.isBlank()) ? land : "Schweiz";
        this.gueltigVon = gueltigVon;
        this.gueltigBis = gueltigBis;
    }

    /**
     * True if this address is currently valid: gueltigVon <= today <= gueltigBis (or unbefristet).
     */
    public boolean isAktuell() {
        LocalDate today = LocalDate.now();
        return !gueltigVon.isAfter(today)
                && (gueltigBis == null || !gueltigBis.isBefore(today));
    }

    /**
     * True if this address is pre-recorded for a future date: gueltigVon > today.
     */
    public boolean isVorerfasst() {
        return gueltigVon.isAfter(LocalDate.now());
    }

    /**
     * Returns true if this address overlaps with the given [vonNeu, bisNeu] period.
     * Overlap condition: this.gueltigVon <= bisNeu AND this.gueltigBis >= vonNeu (null = ∞).
     */
    public boolean overlaps(LocalDate vonNeu, LocalDate bisNeu) {
        // this.gueltigVon <= bisNeu  (bisNeu==null means ∞, so always true)
        boolean existingStartsBeforeNewEnd = bisNeu == null || !this.gueltigVon.isAfter(bisNeu);
        // this.gueltigBis >= vonNeu  (this.gueltigBis==null means ∞, so always true)
        boolean existingEndsAfterNewStart = this.gueltigBis == null || !this.gueltigBis.isBefore(vonNeu);
        return existingStartsBeforeNewEnd && existingEndsAfterNewStart;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getAdressId() { return adressId; }
    public String getPersonId() { return personId; }
    public AdressTyp getAdressTyp() { return adressTyp; }
    public String getStrasse() { return strasse; }
    public String getHausnummer() { return hausnummer; }
    public String getPlz() { return plz; }
    public String getOrt() { return ort; }
    public String getLand() { return land; }
    public LocalDate getGueltigVon() { return gueltigVon; }
    public LocalDate getGueltigBis() { return gueltigBis; }

    /** Setter for validity period update (called by Person aggregate). */
    public void setGueltigVon(LocalDate gueltigVon) {
        if (gueltigVon == null) throw new IllegalArgumentException("gueltigVon ist Pflichtfeld");
        this.gueltigVon = gueltigVon;
    }

    public void setGueltigBis(LocalDate gueltigBis) {
        this.gueltigBis = gueltigBis;
    }
}

