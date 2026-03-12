package ch.css.partner.domain.model;

import java.util.UUID;

/**
 * Partner Aggregate Root
 * Represents a partner/customer in the insurance domain.
 * 
 * Invariants:
 * - partnerId is immutable and unique
 * - name is required
 * - email or phone must be present
 */
public class Partner {

    private String partnerId;
    private String name;
    private String email;
    private String phone;
    private String street;
    private String city;
    private String postalCode;
    private String country;
    private PartnerType partnerType;
    private PartnerStatus status;

    public Partner() {
        // For JPA
    }

    public Partner(String name, String email, String phone, PartnerType partnerType) {
        this.partnerId = UUID.randomUUID().toString();
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.partnerType = partnerType;
        this.status = PartnerStatus.ACTIVE;
    }

    // Getters
    public String getPartnerId() {
        return partnerId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getStreet() {
        return street;
    }

    public String getCity() {
        return city;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public PartnerType getPartnerType() {
        return partnerType;
    }

    public PartnerStatus getStatus() {
        return status;
    }

    // Setters (for address update)
    public void updateAddress(String street, String city, String postalCode, String country) {
        this.street = street;
        this.city = city;
        this.postalCode = postalCode;
        this.country = country;
    }

    public void deactivate() {
        this.status = PartnerStatus.INACTIVE;
    }

    public void reactivate() {
        this.status = PartnerStatus.ACTIVE;
    }
}
