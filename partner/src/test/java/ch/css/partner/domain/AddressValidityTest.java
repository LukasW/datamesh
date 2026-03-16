package ch.css.partner.domain;

import ch.css.partner.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdresseGültigkeit – Überlappungslogik Tests")
class AddressValidityTest {

    private static final SocialSecurityNumber SSN = new SocialSecurityNumber("756.1234.5678.97");
    private Person person;

    @BeforeEach
    void setUp() {
        person = new Person("Muster", "Hans", Gender.MALE,
                LocalDate.of(1980, 5, 12), SSN);
    }

    @Test
    @DisplayName("Neue Adresse überlappt mit bestehender → bestehende wird zugeschnitten")
    void overlappingPeriods_existingClipped() {
        // existing: 2020-01-01 – 2020-12-31
        person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31));

        // new: 2020-06-01 – 2021-06-30 → overlaps; existing should be clipped to 2020-05-31
        person.addAddress(AddressType.RESIDENCE, "Str", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2020, 6, 1), LocalDate.of(2021, 6, 30));

        assertEquals(2, person.getAddresses().size());
        Address existing = person.getAddresses().stream()
                .filter(a -> a.getStreet().equals("Str") && a.getHouseNumber().equals("1"))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2020, 5, 31), existing.getValidTo());
    }

    @Test
    @DisplayName("Aneinanderliegende Zeiträume (kein gemeinsamer Tag) → kein Konflikt")
    void adjacentPeriods_noConflict() {
        person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31));

        // 2021-01-01 directly follows → no overlap
        person.addAddress(AddressType.RESIDENCE, "Str", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2021, 1, 1), null);

        assertEquals(2, person.getAddresses().size());
    }

    @Test
    @DisplayName("Gemeinsamer letzter/erster Tag → bestehende wird auf Tag davor zugeschnitten")
    void sharedLastAndFirstDay_existingClipped() {
        person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31));

        // new starts on 2020-12-31 → existing clipped to 2020-12-30
        person.addAddress(AddressType.RESIDENCE, "Str", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2020, 12, 31), LocalDate.of(2021, 12, 31));

        Address existing = person.getAddresses().stream()
                .filter(a -> a.getHouseNumber().equals("1"))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2020, 12, 30), existing.getValidTo());
    }

    @Test
    @DisplayName("Unbefristete Adresse → wird zugeschnitten wenn neue Adresse später beginnt")
    void unbefristetAdresse_clippedOnNewAdresse() {
        // existing: 2020-01-01 – ∞
        person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        // new: 2025-01-01 – ∞ → existing clipped to 2024-12-31
        person.addAddress(AddressType.RESIDENCE, "Str", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2025, 1, 1), null);

        assertEquals(2, person.getAddresses().size());
        Address existing = person.getAddresses().stream()
                .filter(a -> a.getHouseNumber().equals("1"))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2024, 12, 31), existing.getValidTo());
    }

    @Test
    @DisplayName("Neue unbefristete Adresse ersetzt ältere → bestehende zugeschnitten")
    void newUnbefristet_olderClipped() {
        person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        // new starts 2021-01-01 → existing clipped to 2020-12-31
        person.addAddress(AddressType.RESIDENCE, "Str", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2021, 1, 1), null);

        Address existing = person.getAddresses().stream()
                .filter(a -> a.getHouseNumber().equals("1"))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2020, 12, 31), existing.getValidTo());
    }

    @Test
    @DisplayName("Neue Adresse enthält bestehende vollständig → bestehende wird entfernt")
    void newPeriodContainsExisting_existingRemoved() {
        // existing: 2021-01-01 – 2021-12-31
        person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2021, 1, 1), LocalDate.of(2021, 12, 31));

        // new: 2020-01-01 – ∞ → fully contains existing → existing removed
        person.addAddress(AddressType.RESIDENCE, "Str", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        assertEquals(1, person.getAddresses().size());
        assertEquals("2", person.getAddresses().get(0).getHouseNumber());
    }

    @Test
    @DisplayName("Zeitraum vor bestehendem → kein Konflikt")
    void periodBeforeExisting_noConflict() {
        person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31));

        person.addAddress(AddressType.RESIDENCE, "Alt", "5", "3000", "Bern", "Schweiz",
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31));

        assertEquals(2, person.getAddresses().size());
    }

    @Test
    @DisplayName("validFrom nach validTo → IllegalArgumentException beim Erstellen")
    void vonAfterBis_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                        LocalDate.of(2021, 12, 31), LocalDate.of(2020, 1, 1)));
    }

    @Test
    @DisplayName("updateAddressValidity mit Überschneidung → andere Adresse wird angepasst")
    void updateGueltigkeit_overlapping_adjustsOther() {
        String aid1 = person.addAddress(AddressType.RESIDENCE, "Str1", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31));
        person.addAddress(AddressType.RESIDENCE, "Str2", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        // Extend aid1 into 2020 → aid2 should be clipped
        person.updateAddressValidity(aid1,
                LocalDate.of(2015, 1, 1), LocalDate.of(2020, 6, 30));

        Address aid2 = person.getAddresses().stream()
                .filter(a -> a.getHouseNumber().equals("2"))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2020, 7, 1), aid2.getValidFrom());
    }

    @Test
    @DisplayName("updateAddressValidity ohne Überschneidung → erfolgreich")
    void updateGueltigkeit_noOverlap_succeeds() {
        String aid1 = person.addAddress(AddressType.RESIDENCE, "Str1", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31));
        person.addAddress(AddressType.RESIDENCE, "Str2", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        person.updateAddressValidity(aid1,
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 6, 30));

        assertEquals(LocalDate.of(2019, 6, 30),
                person.getAddresses().stream()
                        .filter(a -> a.getAddressId().equals(aid1))
                        .findFirst().orElseThrow().getValidTo());
    }

    // ── PLZ-Validierung ───────────────────────────────────────────────────────

    @Test
    @DisplayName("PLZ mit genau 4 Ziffern → gültig")
    void plz_exactly4Digits_valid() {
        assertDoesNotThrow(() ->
                person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                        LocalDate.of(2020, 1, 1), null));
    }

    @Test
    @DisplayName("PLZ mit 3 Ziffern → IllegalArgumentException")
    void plz_3digits_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                person.addAddress(AddressType.RESIDENCE, "Str", "1", "800", "Zürich", "Schweiz",
                        LocalDate.of(2020, 1, 1), null));
        assertTrue(ex.getMessage().contains("Postal code"));
    }

    @Test
    @DisplayName("PLZ mit 5 Ziffern → IllegalArgumentException")
    void plz_5digits_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                person.addAddress(AddressType.RESIDENCE, "Str", "1", "80010", "Zürich", "Schweiz",
                        LocalDate.of(2020, 1, 1), null));
    }

    @Test
    @DisplayName("PLZ mit Buchstaben → IllegalArgumentException")
    void plz_letters_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                person.addAddress(AddressType.RESIDENCE, "Str", "1", "AB01", "Zürich", "Schweiz",
                        LocalDate.of(2020, 1, 1), null));
    }

    @Test
    @DisplayName("PLZ null → IllegalArgumentException")
    void plz_null_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                person.addAddress(AddressType.RESIDENCE, "Str", "1", null, "Zürich", "Schweiz",
                        LocalDate.of(2020, 1, 1), null));
    }

    @Test
    @DisplayName("isCurrent: Adresse mit heute im Gültigkeitsbereich → true")
    void isCurrent_currentDate_true() {
        String addressId = person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        Address address = person.getAddresses().stream()
                .filter(a -> a.getAddressId().equals(addressId)).findFirst().orElseThrow();
        assertTrue(address.isCurrent());
    }

    @Test
    @DisplayName("isCurrent: Abgelaufene Adresse → false")
    void isCurrent_expired_false() {
        String addressId = person.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31));
        Address address = person.getAddresses().stream()
                .filter(a -> a.getAddressId().equals(addressId)).findFirst().orElseThrow();
        assertFalse(address.isCurrent());
    }
}
