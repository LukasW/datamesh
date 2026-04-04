package ch.yuno.partner.domain.port.in;

import ch.yuno.partner.domain.model.AddressId;
import ch.yuno.partner.domain.model.AddressType;
import ch.yuno.partner.domain.model.Gender;
import ch.yuno.partner.domain.model.PersonId;

import java.time.LocalDate;

/**
 * Inbound port for person command use cases.
 */
public interface PersonCommandUseCase {

    PersonId createPerson(String name, String firstName, Gender gender,
                          LocalDate dateOfBirth, String socialSecurityNumberRaw);

    void updatePersonalData(PersonId personId, String name, String firstName,
                            Gender gender, LocalDate dateOfBirth);

    void deletePerson(PersonId personId);

    AddressId addAddress(PersonId personId, AddressType addressType,
                         String street, String houseNumber, String postalCode, String city, String land,
                         LocalDate validFrom, LocalDate validTo);

    void updateAddressValidity(PersonId personId, AddressId addressId,
                               LocalDate validFrom, LocalDate validTo);

    void deleteAddress(PersonId personId, AddressId addressId);

    /**
     * Assigns an insured number to the person if they don't already have one.
     * Triggered by policy.v1.issued events. Idempotent.
     *
     * @return true if a new number was assigned, false if already insured
     */
    boolean assignInsuredNumberIfAbsent(PersonId personId);
}
