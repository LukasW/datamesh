package ch.css.partner.domain.model;

import ch.css.partner.domain.service.AdresseNotFoundException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root: Person.
 * All state changes are made through this aggregate. Invariants are enforced here.
 */
public class Person {

    private final String personId;
    private String name;
    private String vorname;
    private Geschlecht geschlecht;
    private LocalDate geburtsdatum;
    private final AhvNummer ahvNummer;
    private List<Adresse> adressen;

    /** Constructor for creating a new Person. */
    public Person(String name, String vorname, Geschlecht geschlecht,
                  LocalDate geburtsdatum, AhvNummer ahvNummer) {
        validate(name, vorname, geschlecht, geburtsdatum);
        this.personId = UUID.randomUUID().toString();
        this.name = name;
        this.vorname = vorname;
        this.geschlecht = geschlecht;
        this.geburtsdatum = geburtsdatum;
        this.ahvNummer = ahvNummer;
        this.adressen = new ArrayList<>();
    }

    /** Constructor for reconstructing from persistence. */
    public Person(String personId, String name, String vorname, Geschlecht geschlecht,
                  LocalDate geburtsdatum, AhvNummer ahvNummer) {
        this.personId = personId;
        this.name = name;
        this.vorname = vorname;
        this.geschlecht = geschlecht;
        this.geburtsdatum = geburtsdatum;
        this.ahvNummer = ahvNummer;
        this.adressen = new ArrayList<>();
    }

    // ── Business Methods ──────────────────────────────────────────────────────

    public void updatePersonalien(String name, String vorname, Geschlecht geschlecht,
                                   LocalDate geburtsdatum) {
        validate(name, vorname, geschlecht, geburtsdatum);
        this.name = name;
        this.vorname = vorname;
        this.geschlecht = geschlecht;
        this.geburtsdatum = geburtsdatum;
    }

    /**
     * Adds a new address. Automatically adjusts overlapping addresses of the same type.
     * @return the new adressId
     */
    public String addAdresse(AdressTyp adressTyp,
                              String strasse, String hausnummer, String plz, String ort, String land,
                              LocalDate gueltigVon, LocalDate gueltigBis) {
        adjustOverlappingAdressen(null, adressTyp, gueltigVon, gueltigBis);
        String adressId = UUID.randomUUID().toString();
        Adresse adresse = new Adresse(adressId, personId, adressTyp,
                strasse, hausnummer, plz, ort, land, gueltigVon, gueltigBis);
        adressen.add(adresse);
        return adressId;
    }

    /**
     * Updates an address's validity period. Automatically adjusts overlapping addresses of the same type.
     */
    public void updateAdressGueltigkeit(String adressId, LocalDate gueltigVon, LocalDate gueltigBis) {
        Adresse adresse = findAdresse(adressId);
        if (gueltigBis != null && gueltigVon.isAfter(gueltigBis)) {
            throw new IllegalArgumentException("gueltigVon darf nicht nach gueltigBis liegen");
        }
        adjustOverlappingAdressen(adressId, adresse.getAdressTyp(), gueltigVon, gueltigBis);
        adresse.setGueltigVon(gueltigVon);
        adresse.setGueltigBis(gueltigBis);
    }

    /** Removes an address by ID. */
    public void removeAdresse(String adressId) {
        findAdresse(adressId); // throws AdresseNotFoundException if not found
        adressen.removeIf(a -> a.getAdressId().equals(adressId));
    }

    /** Returns the currently valid address for the given type, or null if none. */
    public Adresse getAktuelleAdresse(AdressTyp adressTyp) {
        return adressen.stream()
                .filter(a -> a.getAdressTyp() == adressTyp && a.isAktuell())
                .findFirst()
                .orElse(null);
    }

    /** Returns all addresses for the given type, sorted chronologically by gueltigVon. */
    public List<Adresse> getAdressverlauf(AdressTyp adressTyp) {
        return adressen.stream()
                .filter(a -> a.getAdressTyp() == adressTyp)
                .sorted(Comparator.comparing(Adresse::getGueltigVon))
                .toList();
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Automatically adjusts existing addresses of the same type that overlap with [vonNeu, bisNeu]:
     * - Existing starts before vonNeu → clip its gueltigBis to vonNeu minus one day
     * - Existing starts within the new period but extends beyond bisNeu → clip its gueltigVon to bisNeu plus one day
     * - Existing is entirely within the new period → remove it
     */
    private void adjustOverlappingAdressen(String excludeAdressId, AdressTyp adressTyp,
                                            LocalDate vonNeu, LocalDate bisNeu) {
        List<Adresse> toRemove = new ArrayList<>();
        for (Adresse existing : adressen) {
            if (existing.getAdressTyp() != adressTyp) continue;
            if (existing.getAdressId().equals(excludeAdressId)) continue;
            if (!existing.overlaps(vonNeu, bisNeu)) continue;

            boolean startsBeforeNew = existing.getGueltigVon().isBefore(vonNeu);
            boolean endsAfterNew = bisNeu != null
                    && (existing.getGueltigBis() == null || existing.getGueltigBis().isAfter(bisNeu));

            if (startsBeforeNew) {
                existing.setGueltigBis(vonNeu.minusDays(1));
            } else if (endsAfterNew) {
                existing.setGueltigVon(bisNeu.plusDays(1));
            } else {
                toRemove.add(existing);
            }
        }
        adressen.removeAll(toRemove);
    }

    private Adresse findAdresse(String adressId) {
        return adressen.stream()
                .filter(a -> a.getAdressId().equals(adressId))
                .findFirst()
                .orElseThrow(() -> new AdresseNotFoundException(adressId));
    }

    private static void validate(String name, String vorname, Geschlecht geschlecht,
                                  LocalDate geburtsdatum) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name ist Pflichtfeld");
        if (vorname == null || vorname.isBlank()) throw new IllegalArgumentException("Vorname ist Pflichtfeld");
        if (geschlecht == null) throw new IllegalArgumentException("Geschlecht ist Pflichtfeld");
        if (geburtsdatum == null) throw new IllegalArgumentException("Geburtsdatum ist Pflichtfeld");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getPersonId() { return personId; }
    public String getName() { return name; }
    public String getVorname() { return vorname; }
    public Geschlecht getGeschlecht() { return geschlecht; }
    public LocalDate getGeburtsdatum() { return geburtsdatum; }
    public AhvNummer getAhvNummer() { return ahvNummer; }
    public List<Adresse> getAdressen() { return adressen; }

    /** Used by JPA adapter to restore persisted addresses. */
    public void setAdressen(List<Adresse> adressen) {
        this.adressen = adressen != null ? new ArrayList<>(adressen) : new ArrayList<>();
    }
}

