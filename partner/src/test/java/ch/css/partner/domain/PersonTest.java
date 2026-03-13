package ch.css.partner.domain;

import ch.css.partner.domain.model.*;
import ch.css.partner.domain.service.AdresseNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Person – Aggregate Tests")
class PersonTest {

    private static final AhvNummer AHV = new AhvNummer("756.1234.5678.97");
    private static final LocalDate GEBURTSDATUM = LocalDate.of(1980, 5, 12);

    private Person person;

    @BeforeEach
    void setUp() {
        person = new Person("Muster", "Hans", Geschlecht.MAENNLICH, GEBURTSDATUM, AHV);
    }

    @Test
    @DisplayName("Neue Person erhält eine UUID als personId")
    void newPerson_hasPersonId() {
        assertNotNull(person.getPersonId());
        assertFalse(person.getPersonId().isBlank());
    }

    @Test
    @DisplayName("Neue Person wird korrekt initialisiert")
    void newPerson_fieldsSet() {
        assertEquals("Muster", person.getName());
        assertEquals("Hans", person.getVorname());
        assertEquals(Geschlecht.MAENNLICH, person.getGeschlecht());
        assertEquals(GEBURTSDATUM, person.getGeburtsdatum());
        assertEquals(AHV, person.getAhvNummer());
        assertTrue(person.getAdressen().isEmpty());
    }

    @Test
    @DisplayName("Pflichtfeld Name null → IllegalArgumentException")
    void missingName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Person(null, "Hans", Geschlecht.MAENNLICH, GEBURTSDATUM, AHV));
    }

    @Test
    @DisplayName("Pflichtfeld Vorname leer → IllegalArgumentException")
    void blankVorname_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Person("Muster", "  ", Geschlecht.MAENNLICH, GEBURTSDATUM, AHV));
    }

    @Test
    @DisplayName("Neue Person ohne AHV-Nummer wird akzeptiert")
    void newPersonWithoutAhv_accepted() {
        Person p = new Person("Muster", "Hans", Geschlecht.MAENNLICH, GEBURTSDATUM, null);
        assertNotNull(p.getPersonId());
        assertNull(p.getAhvNummer());
    }

    @Test
    @DisplayName("Pflichtfeld Geburtsdatum null → IllegalArgumentException")
    void missingGeburtsdatum_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Person("Muster", "Hans", Geschlecht.MAENNLICH, null, AHV));
    }

    @Test
    @DisplayName("updatePersonalien ändert alle Felder korrekt")
    void updatePersonalien_changesFields() {
        person.updatePersonalien("Neuer", "Name", Geschlecht.WEIBLICH, LocalDate.of(1990, 1, 1));
        assertEquals("Neuer", person.getName());
        assertEquals("Name", person.getVorname());
        assertEquals(Geschlecht.WEIBLICH, person.getGeschlecht());
        assertEquals(LocalDate.of(1990, 1, 1), person.getGeburtsdatum());
        // AHV-Nummer bleibt unverändert
        assertEquals(AHV, person.getAhvNummer());
    }

    @Test
    @DisplayName("addAdresse fügt Adresse hinzu und gibt adressId zurück")
    void addAdresse_returnsId() {
        String adressId = person.addAdresse(
                AdressTyp.WOHNADRESSE, "Musterstr.", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        assertNotNull(adressId);
        assertEquals(1, person.getAdressen().size());
        assertEquals(AdressTyp.WOHNADRESSE, person.getAdressen().get(0).getAdressTyp());
    }

    @Test
    @DisplayName("addAdresse mit überlappenden Zeiträumen desselben Typs → erste Adresse wird zugeschnitten")
    void addAdresse_overlapping_adjustsExisting() {
        person.addAdresse(AdressTyp.WOHNADRESSE, "Str1", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        person.addAdresse(AdressTyp.WOHNADRESSE, "Str2", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2021, 1, 1), null);

        assertEquals(2, person.getAdressen().size());
        Adresse erste = person.getAdressen().stream()
                .filter(a -> a.getHausnummer().equals("1")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2020, 12, 31), erste.getGueltigBis());
    }

    @Test
    @DisplayName("addAdresse verschiedener Typen kann überlappen")
    void addAdresse_differentTypes_noConflict() {
        person.addAdresse(AdressTyp.WOHNADRESSE, "Str1", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        assertDoesNotThrow(() ->
                person.addAdresse(AdressTyp.KORRESPONDENZADRESSE, "Str2", "2", "3000", "Bern", "Schweiz",
                        LocalDate.of(2020, 1, 1), null));
        assertEquals(2, person.getAdressen().size());
    }

    @Test
    @DisplayName("removeAdresse entfernt die Adresse aus der Liste")
    void removeAdresse_removes() {
        String adressId = person.addAdresse(
                AdressTyp.WOHNADRESSE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        person.removeAdresse(adressId);
        assertTrue(person.getAdressen().isEmpty());
    }

    @Test
    @DisplayName("removeAdresse mit unbekannter ID → AdresseNotFoundException")
    void removeAdresse_unknownId_throws() {
        assertThrows(AdresseNotFoundException.class,
                () -> person.removeAdresse("unknown-id"));
    }

    @Test
    @DisplayName("getAktuelleAdresse liefert die aktuell gültige Adresse")
    void getAktuelleAdresse_returnsCurrentAddress() {
        person.addAdresse(AdressTyp.WOHNADRESSE, "Alt", "5", "3000", "Bern", "Schweiz",
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31));
        person.addAdresse(AdressTyp.WOHNADRESSE, "Neu", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        Adresse aktuell = person.getAktuelleAdresse(AdressTyp.WOHNADRESSE);
        assertNotNull(aktuell);
        assertEquals("Neu", aktuell.getStrasse());
    }

    @Test
    @DisplayName("getAdressverlauf liefert alle Adressen chronologisch")
    void getAdressverlauf_returnsSortedAddresses() {
        person.addAdresse(AdressTyp.WOHNADRESSE, "Neu", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        person.addAdresse(AdressTyp.WOHNADRESSE, "Alt", "5", "3000", "Bern", "Schweiz",
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31));

        var verlauf = person.getAdressverlauf(AdressTyp.WOHNADRESSE);
        assertEquals(2, verlauf.size());
        assertEquals("Alt", verlauf.get(0).getStrasse());
        assertEquals("Neu", verlauf.get(1).getStrasse());
    }
}

