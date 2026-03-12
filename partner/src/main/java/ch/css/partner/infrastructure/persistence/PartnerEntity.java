package ch.css.partner.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA Entity mapping for Partner
 */
@Entity
@Table(name = "partner")
public class PartnerEntity {

    @Id
    public String partnerId;

    public String name;
    public String email;
    public String phone;
    public String street;
    public String city;
    public String postalCode;
    public String country;
    public String partnerType; // Enum as string
    public String status; // Enum as string

    public PartnerEntity() {
    }
}
