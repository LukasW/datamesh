package ch.css.partner.domain.service;

import ch.css.partner.domain.model.Address;
import ch.css.partner.domain.model.AddressType;
import ch.css.partner.domain.model.Gender;
import ch.css.partner.domain.model.OutboxEvent;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.model.SocialSecurityNumber;
import ch.css.partner.domain.port.out.OutboxRepository;
import ch.css.partner.domain.port.out.PersonRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PersonApplicationService {

    @Inject
    PersonRepository personRepository;

    @Inject
    OutboxRepository outboxRepository;

    // ── Person management ─────────────────────────────────────────────────────

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
                        person.getPersonId(), name, firstName, socialSecurityNumber, dateOfBirth)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", person.getPersonId(), "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person)));
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
                PersonEventPayloadBuilder.buildPersonUpdated(personId, name, firstName)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person)));
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
    }

    public Person findById(String personId) {
        return findOrThrow(personId);
    }

    public List<Person> listAllPersons() {
        return personRepository.search(null, null, null, null);
    }

    public List<Person> searchPersons(String name, String firstName, String socialSecurityNumberRaw, LocalDate dateOfBirth) {
        boolean hasFilter = (name != null && !name.isBlank())
                || (firstName != null && !firstName.isBlank())
                || (socialSecurityNumberRaw != null && !socialSecurityNumberRaw.isBlank())
                || dateOfBirth != null;
        if (!hasFilter) {
            throw new IllegalArgumentException("At least one search field must be provided");
        }
        SocialSecurityNumber socialSecurityNumber = (socialSecurityNumberRaw != null && !socialSecurityNumberRaw.isBlank())
                ? new SocialSecurityNumber(socialSecurityNumberRaw) : null;
        return personRepository.search(name, firstName, socialSecurityNumber, dateOfBirth);
    }

    // ── Address management ────────────────────────────────────────────────────

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
                        street, houseNumber, postalCode, city, land, validFrom, validTo)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person)));
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
                PersonEventPayloadBuilder.buildPersonState(person)));
    }

    @Transactional
    public void deleteAddress(String personId, String addressId) {
        Person person = findOrThrow(personId);
        person.removeAddress(addressId);
        personRepository.save(person);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "person", personId, "PersonState",
                PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
                PersonEventPayloadBuilder.buildPersonState(person)));
    }

    public List<Address> getAddresses(String personId) {
        return findOrThrow(personId).getAddresses();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Person findOrThrow(String personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException(personId));
    }
}
