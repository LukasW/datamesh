package ch.yuno.partner.infrastructure.persistence;

import ch.yuno.partner.domain.model.Address;
import ch.yuno.partner.domain.model.AddressId;
import ch.yuno.partner.domain.model.AddressType;
import ch.yuno.partner.domain.model.InsuredNumber;
import ch.yuno.partner.domain.model.PageRequest;
import ch.yuno.partner.domain.model.PageResult;
import ch.yuno.partner.domain.model.PersonId;
import ch.yuno.partner.domain.model.SocialSecurityNumber;
import ch.yuno.partner.domain.model.Gender;
import ch.yuno.partner.domain.model.Person;
import ch.yuno.partner.domain.port.out.PersonRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PersonJpaAdapter implements PersonRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public Person save(Person person) {
        PersonEntity entity = em.find(PersonEntity.class, person.getPersonId().value());
        if (entity == null) {
            entity = toEntity(person);
            em.persist(entity);
        } else {
            updateEntity(entity, person);
        }
        return person;
    }

    @Override
    public Optional<Person> findById(PersonId personId) {
        List<PersonEntity> results = em.createQuery(
                "SELECT DISTINCT p FROM PersonEntity p LEFT JOIN FETCH p.addresses WHERE p.personId = :id",
                PersonEntity.class)
                .setParameter("id", personId.value())
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public Optional<Person> findBySocialSecurityNumber(SocialSecurityNumber socialSecurityNumber) {
        List<PersonEntity> results = em.createQuery(
                "SELECT p FROM PersonEntity p WHERE p.socialSecurityNumber = :ssn", PersonEntity.class)
                .setParameter("ssn", socialSecurityNumber.getValue())
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public List<Person> search(String name, String firstName, SocialSecurityNumber socialSecurityNumber, LocalDate dateOfBirth) {
        StringBuilder jpql = new StringBuilder("SELECT p FROM PersonEntity p WHERE 1=1");
        if (name != null && !name.isBlank()) jpql.append(" AND LOWER(p.name) LIKE LOWER(:name)");
        if (firstName != null && !firstName.isBlank()) jpql.append(" AND LOWER(p.firstName) LIKE LOWER(:firstName)");
        if (socialSecurityNumber != null) jpql.append(" AND p.socialSecurityNumber = :ssn");
        if (dateOfBirth != null) jpql.append(" AND p.dateOfBirth = :dateOfBirth");

        TypedQuery<PersonEntity> query = em.createQuery(jpql.toString(), PersonEntity.class);
        if (name != null && !name.isBlank()) query.setParameter("name", "%" + name + "%");
        if (firstName != null && !firstName.isBlank()) query.setParameter("firstName", "%" + firstName + "%");
        if (socialSecurityNumber != null) query.setParameter("ssn", socialSecurityNumber.getValue());
        if (dateOfBirth != null) query.setParameter("dateOfBirth", dateOfBirth);

        return query.getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    public PageResult<Person> search(String name, String firstName, SocialSecurityNumber socialSecurityNumber, LocalDate dateOfBirth, PageRequest pageRequest) {
        StringBuilder jpql = new StringBuilder("SELECT p FROM PersonEntity p WHERE 1=1");
        StringBuilder countJpql = new StringBuilder("SELECT COUNT(p) FROM PersonEntity p WHERE 1=1");
        String whereClause = buildPersonWhereClause(name, firstName, socialSecurityNumber, dateOfBirth);
        jpql.append(whereClause);
        countJpql.append(whereClause);

        TypedQuery<Long> countQuery = em.createQuery(countJpql.toString(), Long.class);
        setPersonParameters(countQuery, name, firstName, socialSecurityNumber, dateOfBirth);
        long totalElements = countQuery.getSingleResult();

        TypedQuery<PersonEntity> query = em.createQuery(jpql.toString(), PersonEntity.class);
        setPersonParameters(query, name, firstName, socialSecurityNumber, dateOfBirth);
        query.setFirstResult(pageRequest.page() * pageRequest.size());
        query.setMaxResults(pageRequest.size());

        List<Person> content = query.getResultList().stream().map(this::toDomain).toList();
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.size());
        return new PageResult<>(content, totalElements, totalPages);
    }

    private String buildPersonWhereClause(String name, String firstName, SocialSecurityNumber socialSecurityNumber, LocalDate dateOfBirth) {
        StringBuilder clause = new StringBuilder();
        if (name != null && !name.isBlank()) clause.append(" AND LOWER(p.name) LIKE LOWER(:name)");
        if (firstName != null && !firstName.isBlank()) clause.append(" AND LOWER(p.firstName) LIKE LOWER(:firstName)");
        if (socialSecurityNumber != null) clause.append(" AND p.socialSecurityNumber = :ssn");
        if (dateOfBirth != null) clause.append(" AND p.dateOfBirth = :dateOfBirth");
        return clause.toString();
    }

    private <T> void setPersonParameters(TypedQuery<T> query, String name, String firstName, SocialSecurityNumber socialSecurityNumber, LocalDate dateOfBirth) {
        if (name != null && !name.isBlank()) query.setParameter("name", "%" + name + "%");
        if (firstName != null && !firstName.isBlank()) query.setParameter("firstName", "%" + firstName + "%");
        if (socialSecurityNumber != null) query.setParameter("ssn", socialSecurityNumber.getValue());
        if (dateOfBirth != null) query.setParameter("dateOfBirth", dateOfBirth);
    }

    @Override
    @Transactional
    public void delete(PersonId personId) {
        PersonEntity entity = em.find(PersonEntity.class, personId.value());
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    public boolean existsBySocialSecurityNumber(SocialSecurityNumber socialSecurityNumber) {
        Long count = em.createQuery(
                "SELECT COUNT(p) FROM PersonEntity p WHERE p.socialSecurityNumber = :ssn", Long.class)
                .setParameter("ssn", socialSecurityNumber.getValue())
                .getSingleResult();
        return count > 0;
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private PersonEntity toEntity(Person person) {
        PersonEntity e = new PersonEntity();
        e.setPersonId(person.getPersonId().value());
        e.setName(person.getName());
        e.setFirstName(person.getFirstName());
        e.setGender(person.getGender().name());
        e.setDateOfBirth(person.getDateOfBirth());
        e.setSocialSecurityNumber(person.getSocialSecurityNumber() != null ? person.getSocialSecurityNumber().getValue() : null);
        e.setInsuredNumber(person.getInsuredNumber() != null ? person.getInsuredNumber().value() : null);
        for (Address a : person.getAddresses()) {
            AddressEntity ae = toAddressEntity(a, e);
            e.getAddresses().add(ae);
        }
        return e;
    }

    private void updateEntity(PersonEntity e, Person person) {
        e.setName(person.getName());
        e.setFirstName(person.getFirstName());
        e.setGender(person.getGender().name());
        e.setDateOfBirth(person.getDateOfBirth());
        e.setInsuredNumber(person.getInsuredNumber() != null ? person.getInsuredNumber().value() : null);

        // sync addresses: remove deleted, update existing, add new
        e.getAddresses().removeIf(ae ->
                person.getAddresses().stream().noneMatch(a -> a.getAddressId().value().equals(ae.getAddressId())));
        for (Address a : person.getAddresses()) {
            AddressEntity existing = e.getAddresses().stream()
                    .filter(ae -> ae.getAddressId().equals(a.getAddressId().value()))
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.setValidFrom(a.getValidFrom());
                existing.setValidTo(a.getValidTo());
                existing.setStreet(a.getStreet());
                existing.setHouseNumber(a.getHouseNumber());
                existing.setPostalCode(a.getPostalCode());
                existing.setCity(a.getCity());
                existing.setLand(a.getLand());
            } else {
                e.getAddresses().add(toAddressEntity(a, e));
            }
        }
    }

    private AddressEntity toAddressEntity(Address a, PersonEntity personEntity) {
        AddressEntity ae = new AddressEntity();
        ae.setAddressId(a.getAddressId().value());
        ae.setPerson(personEntity);
        ae.setAddressType(a.getAddressType().name());
        ae.setStreet(a.getStreet());
        ae.setHouseNumber(a.getHouseNumber());
        ae.setPostalCode(a.getPostalCode());
        ae.setCity(a.getCity());
        ae.setLand(a.getLand());
        ae.setValidFrom(a.getValidFrom());
        ae.setValidTo(a.getValidTo());
        return ae;
    }

    private Person toDomain(PersonEntity e) {
        Person person = new Person(
                PersonId.of(e.getPersonId()),
                e.getName(),
                e.getFirstName(),
                Gender.valueOf(e.getGender()),
                e.getDateOfBirth(),
                e.getSocialSecurityNumber() != null ? new SocialSecurityNumber(e.getSocialSecurityNumber()) : null,
                e.getInsuredNumber() != null ? new InsuredNumber(e.getInsuredNumber()) : null
        );
        List<Address> addresses = new ArrayList<>();
        for (AddressEntity ae : e.getAddresses()) {
            addresses.add(new Address(
                    AddressId.of(ae.getAddressId()),
                    PersonId.of(e.getPersonId()),
                    AddressType.valueOf(ae.getAddressType()),
                    ae.getStreet(),
                    ae.getHouseNumber(),
                    ae.getPostalCode(),
                    ae.getCity(),
                    ae.getLand(),
                    ae.getValidFrom(),
                    ae.getValidTo()
            ));
        }
        person.setAddresses(addresses);
        return person;
    }
}
