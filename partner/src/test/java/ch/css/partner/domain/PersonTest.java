package ch.css.partner.domain;

import ch.css.partner.domain.model.*;
import ch.css.partner.domain.service.AddressNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Person – Aggregate Tests")
class PersonTest {

    private static final SocialSecurityNumber SSN = new SocialSecurityNumber("756.1234.5678.97");
    private static final LocalDate DATE_OF_BIRTH = LocalDate.of(1980, 5, 12);

    private Person person;

    @BeforeEach
    void setUp() {
        person = new Person("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, SSN);
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
        assertEquals("Hans", person.getFirstName());
        assertEquals(Gender.MALE, person.getGender());
        assertEquals(DATE_OF_BIRTH, person.getDateOfBirth());
        assertEquals(SSN, person.getSocialSecurityNumber());
        assertTrue(person.getAddresses().isEmpty());
    }

    @Test
    @DisplayName("Pflichtfeld Name null → IllegalArgumentException")
    void missingName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Person(null, "Hans", Gender.MALE, DATE_OF_BIRTH, SSN));
    }

    @Test
    @DisplayName("Pflichtfeld Vorname leer → IllegalArgumentException")
    void blankVorname_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Person("Muster", "  ", Gender.MALE, DATE_OF_BIRTH, SSN));
    }

    @Test
    @DisplayName("Neue Person ohne AHV-Nummer wird akzeptiert")
    void newPersonWithoutAhv_accepted() {
        Person p = new Person("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, null);
        assertNotNull(p.getPersonId());
        assertNull(p.getSocialSecurityNumber());
    }

    @Test
    @DisplayName("Pflichtfeld Geburtsdatum null → IllegalArgumentException")
    void missingGeburtsdatum_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Person("Muster", "Hans", Gender.MALE, null, SSN));
    }

    @Test
    @DisplayName("updatePersonalData ändert alle Felder korrekt")
    void updatePersonalData_changesFields() {
        person.updatePersonalData("Neuer", "Name", Gender.FEMALE, LocalDate.of(1990, 1, 1));
        assertEquals("Neuer", person.getName());
        assertEquals("Name", person.getFirstName());
        assertEquals(Gender.FEMALE, person.getGender());
        assertEquals(LocalDate.of(1990, 1, 1), person.getDateOfBirth());
        // SSN stays unchanged
        assertEquals(SSN, person.getSocialSecurityNumber());
    }

    @Test
    @DisplayName("addAddress fügt Adresse hinzu und gibt addressId zurück")
    void addAddress_returnsId() {
        String addressId = person.addAddress(
                AddressType.RESIDENCE, "Musterstr.", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        assertNotNull(addressId);
        assertEquals(1, person.getAddresses().size());
        assertEquals(AddressType.RESIDENCE, person.getAddresses().get(0).getAddressType());
    }

    @Test
    @DisplayName("addAddress mit überlappenden Zeiträumen desselben Typs → erste Adresse wird zugeschnitten")
    void addAddress_overlapping_adjustsExisting() {
        person.addAddress(AddressType.RESIDENCE, "Str1", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        person.addAddress(AddressType.RESIDENCE, "Str2", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2021, 1, 1), null);

        assertEquals(2, person.getAddresses().size());
        Address erste = person.getAddresses().stream()
                .filter(a -> a.getHouseNumber().equals("1")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2020, 12, 31), erste.getValidTo());
    }

    @Test
    @DisplayName("addAddress verschiedener Typen kann überlappen")
    void addAddress_differentTypes_noConflict() {
        person.addAddress(AddressType.RESIDENCE, "Str1", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        assertDoesNotThrow(() ->
                person.addAddress(AddressType.CORRESPONDENCE, "Str2", "2", "3000", "Bern", "Schweiz",
                        LocalDate.of(2020, 1, 1), null));
        assertEquals(2, person.getAddresses().size());
    }

    @Test
    @DisplayName("removeAddress entfernt die Adresse aus der Liste")
    void removeAddress_removes() {
        String addressId = person.addAddress(
                AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        person.removeAddress(addressId);
        assertTrue(person.getAddresses().isEmpty());
    }

    @Test
    @DisplayName("removeAddress mit unbekannter ID → AddressNotFoundException")
    void removeAddress_unknownId_throws() {
        assertThrows(AddressNotFoundException.class,
                () -> person.removeAddress("unknown-id"));
    }

    @Test
    @DisplayName("getCurrentAddress liefert die aktuell gültige Adresse")
    void getCurrentAddress_returnsCurrentAddress() {
        person.addAddress(AddressType.RESIDENCE, "Alt", "5", "3000", "Bern", "Schweiz",
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31));
        person.addAddress(AddressType.RESIDENCE, "Neu", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        Address current = person.getCurrentAddress(AddressType.RESIDENCE);
        assertNotNull(current);
        assertEquals("Neu", current.getStreet());
    }

    @Test
    @DisplayName("getAddressHistory liefert alle Adressen chronologisch")
    void getAddressHistory_returnsSortedAddresses() {
        person.addAddress(AddressType.RESIDENCE, "Neu", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        person.addAddress(AddressType.RESIDENCE, "Alt", "5", "3000", "Bern", "Schweiz",
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31));

        var history = person.getAddressHistory(AddressType.RESIDENCE);
        assertEquals(2, history.size());
        assertEquals("Alt", history.get(0).getStreet());
        assertEquals("Neu", history.get(1).getStreet());
    }
}
