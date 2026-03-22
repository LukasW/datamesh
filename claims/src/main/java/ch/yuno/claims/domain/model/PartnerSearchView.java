package ch.yuno.claims.domain.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Read Model – materialized view of a Partner, built from person.v1.state events.
 * Used by the Claims domain for FNOL partner search.
 * Contains only the fields needed for identification and search (GDPR data minimization).
 * dateOfBirth and socialSecurityNumber allow agents to verify identity
 * when multiple partners share the same name (e.g. "Müller").
 */
public record PartnerSearchView(
        String partnerId,
        String lastName,
        String firstName,
        LocalDate dateOfBirth,
        String socialSecurityNumber,
        String insuredNumber
) {
    private static final DateTimeFormatter CH_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public PartnerSearchView {
        if (partnerId == null || partnerId.isBlank())
            throw new IllegalArgumentException("partnerId is required");
        if (lastName == null || lastName.isBlank())
            throw new IllegalArgumentException("lastName is required");
        if (firstName == null || firstName.isBlank())
            throw new IllegalArgumentException("firstName is required");
        // dateOfBirth, socialSecurityNumber, insuredNumber are nullable
    }

    /** Full name for display: "Hans Müller". */
    public String fullName() {
        return firstName + " " + lastName;
    }

    /** Formatted date of birth for Swiss locale: dd.MM.yyyy. */
    public String formattedDateOfBirth() {
        return dateOfBirth != null ? dateOfBirth.format(CH_DATE) : null;
    }

    /**
     * Masked social security number for display: 756.****.****.97
     * Only first group and check digits visible.
     */
    public String maskedSocialSecurityNumber() {
        if (socialSecurityNumber == null) return null;
        if (socialSecurityNumber.length() < 16) return socialSecurityNumber;
        return socialSecurityNumber.substring(0, 4) + "****.****."+
                socialSecurityNumber.substring(14);
    }

    /** Returns true if this partner has an active insurance relationship. */
    public boolean isInsured() {
        return insuredNumber != null && !insuredNumber.isBlank();
    }
}

