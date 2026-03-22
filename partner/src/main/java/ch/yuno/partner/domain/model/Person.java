package ch.yuno.partner.domain.model;

import ch.yuno.partner.domain.service.AddressNotFoundException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Aggregate Root: Person.
 * All state changes are made through this aggregate. Invariants are enforced here.
 */
public class Person {

    private final PersonId personId;
    private String name;
    private String firstName;
    private Gender gender;
    private LocalDate dateOfBirth;
    private final SocialSecurityNumber socialSecurityNumber;
    private InsuredNumber insuredNumber; // nullable – null until first policy is activated
    private List<Address> addresses;

    /** Constructor for creating a new Person. */
    public Person(String name, String firstName, Gender gender,
                  LocalDate dateOfBirth, SocialSecurityNumber socialSecurityNumber) {
        validate(name, firstName, gender, dateOfBirth);
        this.personId = PersonId.generate();
        this.name = name;
        this.firstName = firstName;
        this.gender = gender;
        this.dateOfBirth = dateOfBirth;
        this.socialSecurityNumber = socialSecurityNumber;
        this.insuredNumber = null;
        this.addresses = new ArrayList<>();
    }

    /** Constructor for reconstructing from persistence (without insuredNumber – backward compat). */
    public Person(PersonId personId, String name, String firstName, Gender gender,
                  LocalDate dateOfBirth, SocialSecurityNumber socialSecurityNumber) {
        this(personId, name, firstName, gender, dateOfBirth, socialSecurityNumber, null);
    }

    /** Constructor for reconstructing from persistence (with insuredNumber). */
    public Person(PersonId personId, String name, String firstName, Gender gender,
                  LocalDate dateOfBirth, SocialSecurityNumber socialSecurityNumber,
                  InsuredNumber insuredNumber) {
        this.personId = personId;
        this.name = name;
        this.firstName = firstName;
        this.gender = gender;
        this.dateOfBirth = dateOfBirth;
        this.socialSecurityNumber = socialSecurityNumber;
        this.insuredNumber = insuredNumber;
        this.addresses = new ArrayList<>();
    }

    // ── Business Methods ──────────────────────────────────────────────────────

    public void updatePersonalData(String name, String firstName, Gender gender,
                                   LocalDate dateOfBirth) {
        validate(name, firstName, gender, dateOfBirth);
        this.name = name;
        this.firstName = firstName;
        this.gender = gender;
        this.dateOfBirth = dateOfBirth;
    }

    /**
     * Adds a new address. Automatically adjusts overlapping addresses of the same type.
     * @return the new addressId
     */
    public AddressId addAddress(AddressType addressType,
                             String street, String houseNumber, String postalCode, String city, String land,
                             LocalDate validFrom, LocalDate validTo) {
        adjustOverlappingAddresses(null, addressType, validFrom, validTo);
        AddressId addressId = AddressId.generate();
        Address address = new Address(addressId, personId, addressType,
                street, houseNumber, postalCode, city, land, validFrom, validTo);
        addresses.add(address);
        return addressId;
    }

    /**
     * Updates an address's validity period. Automatically adjusts overlapping addresses of the same type.
     */
    public void updateAddressValidity(AddressId addressId, LocalDate validFrom, LocalDate validTo) {
        Address address = findAddress(addressId);
        if (validTo != null && validFrom.isAfter(validTo)) {
            throw new IllegalArgumentException("validFrom must not be after validTo");
        }
        adjustOverlappingAddresses(addressId, address.getAddressType(), validFrom, validTo);
        address.setValidFrom(validFrom);
        address.setValidTo(validTo);
    }

    /** Removes an address by ID. */
    public void removeAddress(AddressId addressId) {
        findAddress(addressId); // throws AddressNotFoundException if not found
        addresses.removeIf(a -> a.getAddressId().equals(addressId));
    }

    /** Returns the currently valid address for the given type, or null if none. */
    public Address getCurrentAddress(AddressType addressType) {
        return addresses.stream()
                .filter(a -> a.getAddressType() == addressType && a.isCurrent())
                .findFirst()
                .orElse(null);
    }

    /** Returns all addresses for the given type, sorted chronologically by validFrom. */
    public List<Address> getAddressHistory(AddressType addressType) {
        return addresses.stream()
                .filter(a -> a.getAddressType() == addressType)
                .sorted(Comparator.comparing(Address::getValidFrom))
                .toList();
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Automatically adjusts existing addresses of the same type that overlap with [fromNew, toNew]:
     * - Existing starts before fromNew → clip its validTo to fromNew minus one day
     * - Existing starts within the new period but extends beyond toNew → clip its validFrom to toNew plus one day
     * - Existing is entirely within the new period → remove it
     */
    private void adjustOverlappingAddresses(AddressId excludeAddressId, AddressType addressType,
                                             LocalDate fromNew, LocalDate toNew) {
        List<Address> toRemove = new ArrayList<>();
        for (Address existing : addresses) {
            if (existing.getAddressType() != addressType) continue;
            if (existing.getAddressId().equals(excludeAddressId)) continue;
            if (!existing.overlaps(fromNew, toNew)) continue;

            boolean startsBeforeNew = existing.getValidFrom().isBefore(fromNew);
            boolean endsAfterNew = toNew != null
                    && (existing.getValidTo() == null || existing.getValidTo().isAfter(toNew));

            if (startsBeforeNew) {
                existing.setValidTo(fromNew.minusDays(1));
            } else if (endsAfterNew) {
                existing.setValidFrom(toNew.plusDays(1));
            } else {
                toRemove.add(existing);
            }
        }
        addresses.removeAll(toRemove);
    }

    private Address findAddress(AddressId addressId) {
        return addresses.stream()
                .filter(a -> a.getAddressId().equals(addressId))
                .findFirst()
                .orElseThrow(() -> new AddressNotFoundException(addressId.value()));
    }

    private static void validate(String name, String firstName, Gender gender,
                                  LocalDate dateOfBirth) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Last name is required");
        if (firstName == null || firstName.isBlank()) throw new IllegalArgumentException("First name is required");
        if (gender == null) throw new IllegalArgumentException("Gender is required");
        if (dateOfBirth == null) throw new IllegalArgumentException("Date of birth is required");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public PersonId getPersonId() { return personId; }
    public String getName() { return name; }
    public String getFirstName() { return firstName; }
    public Gender getGender() { return gender; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public SocialSecurityNumber getSocialSecurityNumber() { return socialSecurityNumber; }
    public InsuredNumber getInsuredNumber() { return insuredNumber; }
    public List<Address> getAddresses() { return addresses; }

    /** Returns true if this person has an active insurance relationship. */
    public boolean isInsured() {
        return insuredNumber != null;
    }

    /**
     * Assigns an insured number to this person.
     * Idempotent: does nothing if already assigned.
     *
     * @return true if a new number was assigned, false if already had one
     */
    public boolean assignInsuredNumber(InsuredNumber number) {
        if (number == null) throw new IllegalArgumentException("Insured number must not be null");
        if (this.insuredNumber != null) return false; // already insured → idempotent
        this.insuredNumber = number;
        return true;
    }

    /** Used by JPA adapter to restore persisted addresses. */
    public void setAddresses(List<Address> addresses) {
        this.addresses = addresses != null ? new ArrayList<>(addresses) : new ArrayList<>();
    }
}
