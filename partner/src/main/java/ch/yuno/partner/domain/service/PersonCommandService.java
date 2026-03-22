package ch.yuno.partner.domain.service;

import ch.yuno.partner.domain.model.AddressId;
import ch.yuno.partner.domain.model.AddressType;
import ch.yuno.partner.domain.model.Gender;
import ch.yuno.partner.domain.model.InsuredNumber;
import ch.yuno.partner.domain.model.Person;
import ch.yuno.partner.domain.model.PersonId;
import ch.yuno.partner.domain.model.SocialSecurityNumber;
import ch.yuno.partner.domain.port.out.InsuredNumberGenerator;
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

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(PersonCommandService.class);

    @Inject
    PersonRepository personRepository;

    @Inject
    OutboxRepository outboxRepository;

    @Inject
    PiiEncryptor piiEncryptor;

    @Inject
    InsuredNumberGenerator insuredNumberGenerator;

    @Transactional
    public PersonId createPerson(String name, String firstName, Gender gender,
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
                UUID.randomUUID(), "person", person.getPersonId().value(), "PersonCreated",
                PersonEventPayloadBuilder.TOPIC_PERSON_CREATED,
                PersonEventPayloadBuilder.buildPersonCreated(
                        person.getPersonId().value(), name, firstName, socialSecurityNumber, dateOfBirth,
                        piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", person.getPersonId().value(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
        return person.getPersonId();
    }

    @Transactional
    public void updatePersonalData(PersonId personId, String name, String firstName,
                                   Gender gender, LocalDate dateOfBirth) {
        Person person = findOrThrow(personId);
        person.updatePersonalData(name, firstName, gender, dateOfBirth);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonUpdated",
                PersonEventPayloadBuilder.TOPIC_PERSON_UPDATED,
                PersonEventPayloadBuilder.buildPersonUpdated(personId.value(), name, firstName, piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    @Transactional
    public void deletePerson(PersonId personId) {
        findOrThrow(personId);
        personRepository.delete(personId);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonDeleted",
                PersonEventPayloadBuilder.TOPIC_PERSON_DELETED,
                PersonEventPayloadBuilder.buildPersonDeleted(personId.value())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonStateDeleted(personId.value())));
        // ADR-009: Crypto-shredding — delete the per-entity encryption key.
        // All previously encrypted PII in Kafka events and Iceberg Parquet files
        // becomes permanently unreadable after this call.
        piiEncryptor.deleteKey(personId.value());
    }

    @Transactional
    public AddressId addAddress(PersonId personId, AddressType addressType,
                             String street, String houseNumber, String postalCode, String city, String land,
                             LocalDate validFrom, LocalDate validTo) {

        Person person = findOrThrow(personId);
        AddressId addressId = person.addAddress(addressType, street, houseNumber, postalCode, city, land, validFrom, validTo);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "AddressAdded",
                PersonEventPayloadBuilder.TOPIC_PERSON_ADDRESS_ADDED,
                PersonEventPayloadBuilder.buildAddressAdded(personId.value(), addressId.value(), addressType,
                        street, houseNumber, postalCode, city, land, validFrom, validTo,
                        piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
        return addressId;
    }

    @Transactional
    public void updateAddressValidity(PersonId personId, AddressId addressId,
                                      LocalDate validFrom, LocalDate validTo) {
        Person person = findOrThrow(personId);
        person.updateAddressValidity(addressId, validFrom, validTo);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "AddressUpdated",
                PersonEventPayloadBuilder.TOPIC_PERSON_ADDRESS_UPDATED,
                PersonEventPayloadBuilder.buildAddressUpdated(personId.value(), addressId.value(), validFrom, validTo)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    @Transactional
    public void deleteAddress(PersonId personId, AddressId addressId) {
        Person person = findOrThrow(personId);
        person.removeAddress(addressId);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    // ── Insured Number Assignment ─────────────────────────────────────────────

    /**
     * Assigns an insured number to the person identified by personId,
     * if they don't already have one.
     * Triggered by policy.v1.issued events.
     * Idempotent: safe to call multiple times for the same person.
     *
     * @return true if a new number was assigned, false if already insured
     */
    @Transactional
    public boolean assignInsuredNumberIfAbsent(PersonId personId) {
        Person person = findOrThrow(personId);

        if (person.isInsured()) {
            LOG.infof("Person %s already has insured number %s – skipping",
                    personId, person.getInsuredNumber().formatted());
            return false;
        }

        InsuredNumber number = insuredNumberGenerator.nextInsuredNumber();
        person.assignInsuredNumber(number);
        personRepository.save(person);

        // Publish PersonUpdated + PersonState via Outbox (same TX)
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonUpdated",
                PersonEventPayloadBuilder.TOPIC_PERSON_UPDATED,
                PersonEventPayloadBuilder.buildPersonUpdated(personId.value(), person.getName(),
                        person.getFirstName(), person.getInsuredNumber(), piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));

        LOG.infof("Assigned insured number %s to person %s", number.formatted(), personId);
        return true;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Person findOrThrow(PersonId personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException(personId.value()));
    }
}
