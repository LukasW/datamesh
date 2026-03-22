package ch.yuno.claims.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_search_view")
public class PartnerSearchViewEntity {

    @Id
    @Column(name = "partner_id", length = 36)
    private String partnerId;

    @Column(name = "last_name", nullable = false, length = 255)
    private String lastName;

    @Column(name = "first_name", nullable = false, length = 255)
    private String firstName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "social_security_number", length = 16)
    private String socialSecurityNumber;

    @Column(name = "insured_number", length = 11)
    private String insuredNumber;

    @Column(name = "upserted_at", nullable = false)
    private LocalDateTime upsertedAt;

    @PrePersist
    @PreUpdate
    protected void onUpsert() {
        upsertedAt = LocalDateTime.now();
    }

    public String getPartnerId() { return partnerId; }
    public void setPartnerId(String partnerId) { this.partnerId = partnerId; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getSocialSecurityNumber() { return socialSecurityNumber; }
    public void setSocialSecurityNumber(String socialSecurityNumber) { this.socialSecurityNumber = socialSecurityNumber; }
    public String getInsuredNumber() { return insuredNumber; }
    public void setInsuredNumber(String insuredNumber) { this.insuredNumber = insuredNumber; }
    public LocalDateTime getUpsertedAt() { return upsertedAt; }
}

