package ch.yuno.partner.infrastructure.messaging;

import ch.yuno.partner.domain.model.AddressId;
import ch.yuno.partner.domain.model.AddressType;
import ch.yuno.partner.domain.model.Person;
import ch.yuno.partner.domain.model.PersonId;
import ch.yuno.partner.domain.port.out.OutboxRepository;
import ch.yuno.partner.domain.port.out.PersonEventPublisher;
import ch.yuno.partner.domain.port.out.PiiEncryptor;
import ch.yuno.partner.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Infrastructure adapter that implements the PersonEventPublisher port.
 * Persists domain events via the transactional outbox pattern, using
 * PersonEventPayloadBuilder for JSON serialization and PiiEncryptor for ADR-009 compliance.
 */
@ApplicationScoped
public class PersonEventPublisherAdapter implements PersonEventPublisher {

    @Inject
    OutboxRepository outboxRepository;

    @Inject
    PiiEncryptor piiEncryptor;

    @Override
    public void personCreated(Person person) {
        String personId = person.getPersonId().value();
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonCreated",
                PersonEventPayloadBuilder.TOPIC_PERSON_CREATED,
                PersonEventPayloadBuilder.buildPersonCreated(
                        personId, person.getName(), person.getFirstName(),
                        person.getSocialSecurityNumber(), person.getDateOfBirth(),
                        piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    @Override
    public void personUpdated(Person person) {
        String personId = person.getPersonId().value();
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonUpdated",
                PersonEventPayloadBuilder.TOPIC_PERSON_UPDATED,
                PersonEventPayloadBuilder.buildPersonUpdated(
                        personId, person.getName(), person.getFirstName(),
                        person.getInsuredNumber(), piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    @Override
    public void personDeleted(PersonId personId) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonDeleted",
                PersonEventPayloadBuilder.TOPIC_PERSON_DELETED,
                PersonEventPayloadBuilder.buildPersonDeleted(personId.value())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId.value(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonStateDeleted(personId.value())));
    }

    @Override
    public void addressAdded(Person person, AddressId addressId, AddressType addressType,
                             String street, String houseNumber, String postalCode, String city, String land,
                             LocalDate validFrom, LocalDate validTo) {
        String personId = person.getPersonId().value();
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "AddressAdded",
                PersonEventPayloadBuilder.TOPIC_PERSON_ADDRESS_ADDED,
                PersonEventPayloadBuilder.buildAddressAdded(
                        personId, addressId.value(), addressType,
                        street, houseNumber, postalCode, city, land, validFrom, validTo,
                        piiEncryptor)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    @Override
    public void addressUpdated(Person person, AddressId addressId, LocalDate validFrom, LocalDate validTo) {
        String personId = person.getPersonId().value();
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "AddressUpdated",
                PersonEventPayloadBuilder.TOPIC_PERSON_ADDRESS_UPDATED,
                PersonEventPayloadBuilder.buildAddressUpdated(personId, addressId.value(), validFrom, validTo)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }

    @Override
    public void stateChanged(Person person) {
        String personId = person.getPersonId().value();
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));
    }
}
