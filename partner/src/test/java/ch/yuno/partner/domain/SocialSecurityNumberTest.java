package ch.yuno.partner.domain;

import ch.yuno.partner.domain.model.SocialSecurityNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SocialSecurityNumber – Value Object Tests")
class SocialSecurityNumberTest {

    @Test
    @DisplayName("Gültige AHV-Nummer mit Punkten wird akzeptiert und formatiert")
    void validAhvWithDots_succeeds() {
        SocialSecurityNumber ssn = new SocialSecurityNumber("756.1234.5678.97");
        assertEquals("7561234567897", ssn.getValue());
        assertEquals("756.1234.5678.97", ssn.formatted());
    }

    @Test
    @DisplayName("Gültige AHV-Nummer ohne Punkte (rohe Ziffern) wird akzeptiert")
    void validAhvRawDigits_succeeds() {
        SocialSecurityNumber ssn = new SocialSecurityNumber("7561234567897");
        assertEquals("756.1234.5678.97", ssn.formatted());
    }

    @Test
    @DisplayName("Ungültige Prüfziffer wird abgelehnt")
    void invalidCheckDigit_throws() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new SocialSecurityNumber("756.0000.0000.00"));
        assertTrue(ex.getMessage().contains("check digit"));
    }

    @Test
    @DisplayName("AHV-Nummer beginnt nicht mit 756 → wird abgelehnt")
    void notStartingWith756_throws() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new SocialSecurityNumber("755.1234.5678.97"));
        assertTrue(ex.getMessage().contains("756"));
    }

    @Test
    @DisplayName("AHV-Nummer mit weniger als 13 Ziffern → wird abgelehnt")
    void tooShort_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new SocialSecurityNumber("756.1234.5678"));
    }

    @Test
    @DisplayName("AHV-Nummer mit mehr als 13 Ziffern → wird abgelehnt")
    void tooLong_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new SocialSecurityNumber("756.1234.5678.970"));
    }

    @Test
    @DisplayName("Leere AHV-Nummer → wird abgelehnt")
    void blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> new SocialSecurityNumber(""));
        assertThrows(IllegalArgumentException.class, () -> new SocialSecurityNumber(null));
    }

    @Test
    @DisplayName("AHV-Nummer Gleichheit basiert auf Wert")
    void equality_basedOnValue() {
        SocialSecurityNumber a = new SocialSecurityNumber("756.1234.5678.97");
        SocialSecurityNumber b = new SocialSecurityNumber("7561234567897");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("toString liefert formatierten Wert")
    void toString_returnsFormatted() {
        SocialSecurityNumber ssn = new SocialSecurityNumber("7561234567897");
        assertEquals("756.1234.5678.97", ssn.toString());
    }

    @Test
    @DisplayName("Weitere gültige AHV-Nummern aus den Tests werden akzeptiert")
    void otherValidAhvNumbers_succeed() {
        assertDoesNotThrow(() -> new SocialSecurityNumber("756.9217.0769.85"));
        assertDoesNotThrow(() -> new SocialSecurityNumber("756.3456.7890.02"));
        assertDoesNotThrow(() -> new SocialSecurityNumber("756.8754.4321.86"));
        assertDoesNotThrow(() -> new SocialSecurityNumber("756.2984.7562.72"));
        assertDoesNotThrow(() -> new SocialSecurityNumber("756.5432.1987.61"));
        assertDoesNotThrow(() -> new SocialSecurityNumber("756.7654.3219.89"));
    }
}
