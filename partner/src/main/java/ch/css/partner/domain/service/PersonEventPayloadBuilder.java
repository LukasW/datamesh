package ch.css.partner.domain.service;

import ch.css.partner.domain.model.Address;
import ch.css.partner.domain.model.AddressType;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.model.SocialSecurityNumber;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Builds JSON payloads for person domain events written to the outbox table.
 * Produces byte-for-byte identical output to the former PersonKafkaAdapter,
 * ensuring downstream consumers require no changes.
 * No framework dependencies – pure domain helper.
 */
public final class PersonEventPayloadBuilder {

    public static final String TOPIC_PERSON_CREATED         = "person.v1.created";
    public static final String TOPIC_PERSON_UPDATED         = "person.v1.updated";
    public static final String TOPIC_PERSON_DELETED         = "person.v1.deleted";
    public static final String TOPIC_PERSON_ADDRESS_ADDED   = "person.v1.address-added";
    public static final String TOPIC_PERSON_ADDRESS_UPDATED = "person.v1.address-updated";
    public static final String TOPIC_PERSON_STATE           = "person.v1.state";

    private PersonEventPayloadBuilder() {}

    public static String buildPersonCreated(String personId, String name, String firstName,
                                            SocialSecurityNumber socialSecurityNumber,
                                            LocalDate dateOfBirth) {
        String ssnStr = socialSecurityNumber != null ? socialSecurityNumber.formatted() : null;
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PersonCreated\",\"personId\":\"%s\"," +
                "\"name\":\"%s\",\"firstName\":\"%s\",\"socialSecurityNumber\":%s," +
                "\"dateOfBirth\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, name, firstName,
                ssnStr != null ? "\"" + ssnStr + "\"" : "null",
                dateOfBirth, Instant.now());
    }

    public static String buildPersonUpdated(String personId, String name, String firstName) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PersonUpdated\",\"personId\":\"%s\"," +
                "\"name\":\"%s\",\"firstName\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, name, firstName, Instant.now());
    }

    public static String buildPersonDeleted(String personId) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PersonDeleted\",\"personId\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, Instant.now());
    }

    public static String buildAddressAdded(String personId, String addressId,
                                           AddressType addressType,
                                           String street, String houseNumber, String postalCode,
                                           String city, String land,
                                           LocalDate validFrom, LocalDate validTo) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"AddressAdded\",\"personId\":\"%s\"," +
                "\"addressId\":\"%s\",\"addressType\":\"%s\"," +
                "\"street\":\"%s\",\"houseNumber\":\"%s\",\"postalCode\":\"%s\"," +
                "\"city\":\"%s\",\"land\":\"%s\"," +
                "\"validFrom\":\"%s\",\"validTo\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, addressId, addressType.name(),
                escape(street), escape(houseNumber), postalCode,
                escape(city), escape(land),
                validFrom, validTo != null ? "\"" + validTo + "\"" : "null",
                Instant.now());
    }

    public static String buildAddressUpdated(String personId, String addressId,
                                             LocalDate validFrom, LocalDate validTo) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"AddressUpdated\",\"personId\":\"%s\"," +
                "\"addressId\":\"%s\",\"validFrom\":\"%s\",\"validTo\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, addressId, validFrom,
                validTo != null ? validTo.toString() : null, Instant.now());
    }

    /**
     * Builds the full current state of a person (Event-Carried State Transfer).
     * Written to the compacted topic person.v1.state on every state change.
     * Downstream services use this to bootstrap and maintain a local materialized view.
     */
    public static String buildPersonState(Person person) {
        SocialSecurityNumber ssn = person.getSocialSecurityNumber();
        String ssnStr = ssn != null ? "\"" + ssn.formatted() + "\"" : "null";

        StringBuilder addresses = new StringBuilder("[");
        List<Address> addressList = person.getAddresses();
        for (int i = 0; i < addressList.size(); i++) {
            Address a = addressList.get(i);
            if (i > 0) addresses.append(",");
            addresses.append(String.format(
                    "{\"addressId\":\"%s\",\"addressType\":\"%s\",\"street\":\"%s\",\"houseNumber\":\"%s\"," +
                    "\"postalCode\":\"%s\",\"city\":\"%s\",\"land\":\"%s\",\"validFrom\":\"%s\",\"validTo\":%s}",
                    a.getAddressId(), a.getAddressType().name(),
                    escape(a.getStreet()), escape(a.getHouseNumber()),
                    a.getPostalCode(), escape(a.getCity()), escape(a.getLand()),
                    a.getValidFrom(),
                    a.getValidTo() != null ? "\"" + a.getValidTo() + "\"" : "null"));
        }
        addresses.append("]");

        return String.format(
                "{\"eventType\":\"PersonState\",\"personId\":\"%s\",\"name\":\"%s\",\"firstName\":\"%s\"," +
                "\"gender\":\"%s\",\"dateOfBirth\":\"%s\",\"socialSecurityNumber\":%s," +
                "\"deleted\":false,\"addresses\":%s,\"timestamp\":\"%s\"}",
                person.getPersonId(), escape(person.getName()), escape(person.getFirstName()),
                person.getGender().name(), person.getDateOfBirth(), ssnStr,
                addresses, Instant.now());
    }

    /**
     * Semantic tombstone for the compacted state topic: signals that the person no longer exists.
     * Downstream services must remove the person from their local materialized view on receipt.
     */
    public static String buildPersonStateDeleted(String personId) {
        return String.format(
                "{\"eventType\":\"PersonState\",\"personId\":\"%s\",\"deleted\":true,\"timestamp\":\"%s\"}",
                personId, Instant.now());
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
