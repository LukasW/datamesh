package ch.yuno.partner.domain;

import ch.yuno.partner.domain.model.InsuredNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InsuredNumber – Value Object Tests")
class InsuredNumberTest {

    @Test
    @DisplayName("Gültiges Format VN-00000042 wird akzeptiert")
    void validFormat_accepted() {
        InsuredNumber vn = new InsuredNumber("VN-00000042");
        assertEquals("VN-00000042", vn.value());
        assertEquals("VN-00000042", vn.formatted());
        assertEquals("VN-00000042", vn.toString());
    }

    @Test
    @DisplayName("fromSequence(42) erzeugt VN-00000042")
    void fromSequence_correctFormat() {
        InsuredNumber vn = InsuredNumber.fromSequence(42);
        assertEquals("VN-00000042", vn.value());
    }

    @Test
    @DisplayName("fromSequence(1) erzeugt VN-00000001")
    void fromSequence_one() {
        assertEquals("VN-00000001", InsuredNumber.fromSequence(1).value());
    }

    @Test
    @DisplayName("fromSequence(99999999) erzeugt VN-99999999")
    void fromSequence_maxValue() {
        assertEquals("VN-99999999", InsuredNumber.fromSequence(99999999).value());
    }

    @Test
    @DisplayName("Null-Wert → IllegalArgumentException")
    void nullValue_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InsuredNumber(null));
    }

    @Test
    @DisplayName("Blank-Wert → IllegalArgumentException")
    void blankValue_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InsuredNumber("  "));
    }

    @Test
    @DisplayName("Ungültiges Format ohne Prefix → IllegalArgumentException")
    void invalidFormat_noPrefix_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InsuredNumber("00000042"));
    }

    @Test
    @DisplayName("Ungültiges Format mit falschem Prefix → IllegalArgumentException")
    void invalidFormat_wrongPrefix_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InsuredNumber("XX-00000042"));
    }

    @Test
    @DisplayName("Ungültiges Format – zu kurz → IllegalArgumentException")
    void invalidFormat_tooShort_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InsuredNumber("VN-0042"));
    }

    @Test
    @DisplayName("Ungültiges Format – zu lang → IllegalArgumentException")
    void invalidFormat_tooLong_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InsuredNumber("VN-000000042"));
    }

    @Test
    @DisplayName("Gleichheit: gleiche Werte sind gleich")
    void equality_sameValue() {
        assertEquals(new InsuredNumber("VN-00000042"), new InsuredNumber("VN-00000042"));
    }

    @Test
    @DisplayName("Gleichheit: verschiedene Werte sind ungleich")
    void equality_differentValue() {
        assertNotEquals(new InsuredNumber("VN-00000042"), new InsuredNumber("VN-00000043"));
    }
}

