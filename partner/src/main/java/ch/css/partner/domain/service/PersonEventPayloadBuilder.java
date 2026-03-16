package ch.css.partner.domain.service;

import ch.css.partner.domain.model.AddressType;
import ch.css.partner.domain.model.SocialSecurityNumber;

import java.time.Instant;
import java.time.LocalDate;
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
                                           AddressType addressType, LocalDate validFrom) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"AddressAdded\",\"personId\":\"%s\"," +
                "\"addressId\":\"%s\",\"addressType\":\"%s\",\"validFrom\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, addressId, addressType.name(), validFrom, Instant.now());
    }

    public static String buildAddressUpdated(String personId, String addressId,
                                             LocalDate validFrom, LocalDate validTo) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"AddressUpdated\",\"personId\":\"%s\"," +
                "\"addressId\":\"%s\",\"validFrom\":\"%s\",\"validTo\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, addressId, validFrom,
                validTo != null ? validTo.toString() : null, Instant.now());
    }
}
