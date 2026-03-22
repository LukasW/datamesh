package ch.yuno.partner.domain.service;

import ch.yuno.partner.domain.model.AddressType;
import ch.yuno.partner.domain.model.Gender;
import ch.yuno.partner.domain.model.Person;
import ch.yuno.partner.domain.model.SocialSecurityNumber;
import ch.yuno.partner.domain.port.out.OutboxRepository;
import ch.yuno.partner.domain.port.out.PersonRepository;
import ch.yuno.partner.domain.port.out.PiiEncryptor;
import ch.yuno.partner.infrastructure.messaging.PersonEventPayloadBuilder;
import ch.yuno.partner.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@ApplicationScoped
public class PersonCommandService {

    @Inject
    PersonRepository personRepository;

    @Inject
    OutboxRepository outboxRepository;

    @Inject
    PiiEncryptor piiEncryptor;

    @Transactional
    public String createPerson(String name, String firstName, Gender gender,
                               LocalDate dateOfBirth, String socialSecurityNumberRaw) {
        SocialSecurityNumber socialSecurityNumber = null;
        if (socialSecurityNumberRaw != null && !socialSecurityNumberRaw.isBlank()) {
            socialSecurityNumber = new SocialSecurityNumber(socialSecurityNumberRaw);
            if (personRepository.existsBySocialSecurityNumber(socialSecurityNumber)) {
                throw new IllegalArgumentException("AHV number already exists: " + socialSecurityNumber.formatted());
            }
        }
        Person person = new Person(name, firstName, gender, dateOfBirth, socialSecurityNumber);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", person.getPersonId(), "PersonCreated",
                PersonEventPayloadBuilder.TOPIC_PERSON_CREATED,
                PersonEventPayloadBuilder.buildPersonCreated(
                        person.getPersonId(), name, firstName, socialSecurityNumber, dateOfBirth,
                        piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", person.getPersonId(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
        return person.getPersonId();
    }

    @Transactional
    public void updatePersonalData(String personId, String name, String firstName,
                                   Gender gender, LocalDate dateOfBirth) {
        Person person = findOrThrow(personId);
        person.updatePersonalData(name, firstName, gender, dateOfBirth);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonUpdated",
                PersonEventPayloadBuilder.TOPIC_PERSON_UPDATED,
                PersonEventPayloadBuilder.buildPersonUpdated(personId, name, firstName, piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    @Transactional
    public void deletePerson(String personId) {
        findOrThrow(personId);
        personRepository.delete(personId);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonDeleted",
                PersonEventPayloadBuilder.TOPIC_PERSON_DELETED,
                PersonEventPayloadBuilder.buildPersonDeleted(personId)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonStateDeleted(personId)));
        // ADR-009: Crypto-shredding — delete the per-entity encryption key.
        // All previously encrypted PII in Kafka events and Iceberg Parquet files
        // becomes permanently unreadable after this call.
        piiEncryptor.deleteKey(personId);
    }

    @Transactional
    public String addAddress(String personId, AddressType addressType,
                             String street, String houseNumber, String postalCode, String city, String land,
                             LocalDate validFrom, LocalDate validTo) {
        Person person = findOrThrow(personId);
        String addressId = person.addAddress(addressType, street, houseNumber, postalCode, city, land, validFrom, validTo);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "AddressAdded",
                PersonEventPayloadBuilder.TOPIC_PERSON_ADDRESS_ADDED,
                PersonEventPayloadBuilder.buildAddressAdded(personId, addressId, addressType,
                        street, houseNumber, postalCode, city, land, validFrom, validTo,
                        piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
        return addressId;
    }

    @Transactional
    public void updateAddressValidity(String personId, String addressId,
                                      LocalDate validFrom, LocalDate validTo) {
        Person person = findOrThrow(personId);
        person.updateAddressValidity(addressId, validFrom, validTo);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "AddressUpdated",
                PersonEventPayloadBuilder.TOPIC_PERSON_ADDRESS_UPDATED,
                PersonEventPayloadBuilder.buildAddressUpdated(personId, addressId, validFrom, validTo)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    @Transactional
    public void deleteAddress(String personId, String addressId) {
        Person person = findOrThrow(personId);
        person.removeAddress(addressId);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Person findOrThrow(String personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException(personId));
    }
}
