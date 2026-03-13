package ch.css.partner.domain;

import ch.css.partner.domain.model.AhvNummer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AhvNummer – Value Object Tests")
class AhvNummerTest {

    @Test
    @DisplayName("Gültige AHV-Nummer mit Punkten wird akzeptiert und formatiert")
    void validAhvWithDots_succeeds() {
        AhvNummer ahv = new AhvNummer("756.1234.5678.97");
        assertEquals("7561234567897", ahv.getValue());
        assertEquals("756.1234.5678.97", ahv.formatted());
    }

    @Test
    @DisplayName("Gültige AHV-Nummer ohne Punkte (rohe Ziffern) wird akzeptiert")
    void validAhvRawDigits_succeeds() {
        AhvNummer ahv = new AhvNummer("7561234567897");
        assertEquals("756.1234.5678.97", ahv.formatted());
    }

    @Test
    @DisplayName("Ungültige Prüfziffer wird abgelehnt")
    void invalidCheckDigit_throws() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new AhvNummer("756.0000.0000.00"));
        assertTrue(ex.getMessage().contains("Prüfziffer"));
    }

    @Test
    @DisplayName("AHV-Nummer beginnt nicht mit 756 → wird abgelehnt")
    void notStartingWith756_throws() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new AhvNummer("755.1234.5678.97"));
        assertTrue(ex.getMessage().contains("756"));
    }

    @Test
    @DisplayName("AHV-Nummer mit weniger als 13 Ziffern → wird abgelehnt")
    void tooShort_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new AhvNummer("756.1234.5678"));
    }

    @Test
    @DisplayName("AHV-Nummer mit mehr als 13 Ziffern → wird abgelehnt")
    void tooLong_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new AhvNummer("756.1234.5678.970"));
    }

    @Test
    @DisplayName("Leere AHV-Nummer → wird abgelehnt")
    void blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> new AhvNummer(""));
        assertThrows(IllegalArgumentException.class, () -> new AhvNummer(null));
    }

    @Test
    @DisplayName("AHV-Nummer Gleichheit basiert auf Wert")
    void equality_basedOnValue() {
        AhvNummer a = new AhvNummer("756.1234.5678.97");
        AhvNummer b = new AhvNummer("7561234567897");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("toString liefert formatierten Wert")
    void toString_returnsFormatted() {
        AhvNummer ahv = new AhvNummer("7561234567897");
        assertEquals("756.1234.5678.97", ahv.toString());
    }

    @Test
    @DisplayName("Weitere gültige AHV-Nummern aus den Tests werden akzeptiert")
    void otherValidAhvNumbers_succeed() {
        assertDoesNotThrow(() -> new AhvNummer("756.9217.0769.85"));
        assertDoesNotThrow(() -> new AhvNummer("756.3456.7890.02"));
        assertDoesNotThrow(() -> new AhvNummer("756.8754.4321.86"));
        assertDoesNotThrow(() -> new AhvNummer("756.2984.7562.72"));
        assertDoesNotThrow(() -> new AhvNummer("756.5432.1987.61"));
        assertDoesNotThrow(() -> new AhvNummer("756.7654.3219.89"));
    }
}

