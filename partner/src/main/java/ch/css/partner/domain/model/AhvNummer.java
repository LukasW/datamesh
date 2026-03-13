package ch.css.partner.domain.model;

import java.util.Objects;

/**
 * Value Object: AHV-Nummer (Swiss social security number).
 * Format: 756.XXXX.XXXX.XX – 13 digits, validated with EAN-13 check digit.
 */
public final class AhvNummer {

    private final String value; // 13 raw digits, no dots

    public AhvNummer(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("AHV-Nummer darf nicht leer sein");
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() != 13) {
            throw new IllegalArgumentException(
                    "AHV-Nummer muss 13 Ziffern haben: " + raw);
        }
        if (!digits.startsWith("756")) {
            throw new IllegalArgumentException(
                    "AHV-Nummer muss mit 756 beginnen: " + raw);
        }
        if (!validateEan13(digits)) {
            throw new IllegalArgumentException(
                    "AHV-Nummer hat ungültige Prüfziffer: " + raw);
        }
        this.value = digits;
    }

    /**
     * EAN-13 check digit validation.
     * Positions 0,2,4,... weight 1; positions 1,3,5,... weight 3.
     * Check digit = (10 - (sum % 10)) % 10.
     */
    private static boolean validateEan13(String digits) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = digits.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        int expectedCheck = (10 - (sum % 10)) % 10;
        return expectedCheck == (digits.charAt(12) - '0');
    }

    /** Raw digits without dots (13 chars). */
    public String getValue() {
        return value;
    }

    /** Returns the formatted representation: "756.XXXX.XXXX.XX" */
    public String formatted() {
        return value.substring(0, 3) + "."
                + value.substring(3, 7) + "."
                + value.substring(7, 11) + "."
                + value.substring(11, 13);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AhvNummer other)) return false;
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return formatted();
    }
}

