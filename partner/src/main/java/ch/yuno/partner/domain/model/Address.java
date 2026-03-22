package ch.yuno.partner.domain.model;

import java.time.LocalDate;

/**
 * Entity: Address – temporal, owned by Person.
 * Invariants:
 * - validFrom must not be after validTo.
 * - Overlap check is enforced at Aggregate level (Person).
 */
public class Address {

    private final String addressId;
    private final String personId;
    private final AddressType addressType;
    private String street;
    private String houseNumber;
    private String postalCode;
    private String city;
    private String land;
    private LocalDate validFrom;
    private LocalDate validTo; // null = open-ended

    public Address(String addressId, String personId, AddressType addressType,
                   String street, String houseNumber, String postalCode, String city, String land,
                   LocalDate validFrom, LocalDate validTo) {
        if (addressId == null) throw new IllegalArgumentException("addressId is required");
        if (personId == null) throw new IllegalArgumentException("personId is required");
        if (addressType == null) throw new IllegalArgumentException("addressType is required");
        if (street == null || street.isBlank()) throw new IllegalArgumentException("street is required");
        if (houseNumber == null || houseNumber.isBlank()) throw new IllegalArgumentException("houseNumber is required");
        if (postalCode == null || !postalCode.matches("\\d{4}")) throw new IllegalArgumentException("Postal code must be exactly 4 digits (e.g. 8001): \"" + postalCode + "\"");
        if (city == null || city.isBlank()) throw new IllegalArgumentException("city is required");
        if (validFrom == null) throw new IllegalArgumentException("validFrom is required");
        if (validTo != null && validFrom.isAfter(validTo)) {
            throw new IllegalArgumentException("validFrom must not be after validTo");
        }
        this.addressId = addressId;
        this.personId = personId;
        this.addressType = addressType;
        this.street = street;
        this.houseNumber = houseNumber;
        this.postalCode = postalCode;
        this.city = city;
        this.land = (land != null && !land.isBlank()) ? land : "Schweiz";
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    /**
     * True if this address is currently valid: validFrom <= today <= validTo (or open-ended).
     */
    public boolean isCurrent() {
        LocalDate today = LocalDate.now();
        return !validFrom.isAfter(today)
                && (validTo == null || !validTo.isBefore(today));
    }

    /**
     * True if this address is pre-recorded for a future date: validFrom > today.
     */
    public boolean isPreRecorded() {
        return validFrom.isAfter(LocalDate.now());
    }

    /**
     * Returns true if this address overlaps with the given [fromNew, toNew] period.
     * Overlap condition: this.validFrom <= toNew AND this.validTo >= fromNew (null = ∞).
     */
    public boolean overlaps(LocalDate fromNew, LocalDate toNew) {
        // this.validFrom <= toNew  (toNew==null means ∞, so always true)
        boolean existingStartsBeforeNewEnd = toNew == null || !this.validFrom.isAfter(toNew);
        // this.validTo >= fromNew  (this.validTo==null means ∞, so always true)
        boolean existingEndsAfterNewStart = this.validTo == null || !this.validTo.isBefore(fromNew);
        return existingStartsBeforeNewEnd && existingEndsAfterNewStart;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getAddressId() { return addressId; }
    public String getPersonId() { return personId; }
    public AddressType getAddressType() { return addressType; }
    public String getStreet() { return street; }
    public String getHouseNumber() { return houseNumber; }
    public String getPostalCode() { return postalCode; }
    public String getCity() { return city; }
    public String getLand() { return land; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }

    /** Setter for validity period update (called by Person aggregate). */
    public void setValidFrom(LocalDate validFrom) {
        if (validFrom == null) throw new IllegalArgumentException("validFrom is required");
        this.validFrom = validFrom;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }
}
