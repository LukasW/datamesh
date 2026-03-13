package ch.css.partner.infrastructure.persistence;

import ch.css.partner.domain.model.Adresse;
import ch.css.partner.domain.model.AdressTyp;
import ch.css.partner.domain.model.AhvNummer;
import ch.css.partner.domain.model.Geschlecht;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.port.out.PersonRepository;
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
        PersonEntity entity = em.find(PersonEntity.class, person.getPersonId());
        if (entity == null) {
            entity = toEntity(person);
            em.persist(entity);
        } else {
            updateEntity(entity, person);
        }
        return person;
    }

    @Override
    public Optional<Person> findById(String personId) {
        PersonEntity entity = em.find(PersonEntity.class, personId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<Person> findByAhvNummer(AhvNummer ahvNummer) {
        List<PersonEntity> results = em.createQuery(
                "SELECT p FROM PersonEntity p WHERE p.ahvNummer = :ahv", PersonEntity.class)
                .setParameter("ahv", ahvNummer.getValue())
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public List<Person> search(String name, String vorname, AhvNummer ahvNummer, LocalDate geburtsdatum) {
        StringBuilder jpql = new StringBuilder("SELECT p FROM PersonEntity p WHERE 1=1");
        if (name != null && !name.isBlank()) jpql.append(" AND LOWER(p.name) LIKE LOWER(:name)");
        if (vorname != null && !vorname.isBlank()) jpql.append(" AND LOWER(p.vorname) LIKE LOWER(:vorname)");
        if (ahvNummer != null) jpql.append(" AND p.ahvNummer = :ahv");
        if (geburtsdatum != null) jpql.append(" AND p.geburtsdatum = :geburtsdatum");

        TypedQuery<PersonEntity> query = em.createQuery(jpql.toString(), PersonEntity.class);
        if (name != null && !name.isBlank()) query.setParameter("name", "%" + name + "%");
        if (vorname != null && !vorname.isBlank()) query.setParameter("vorname", "%" + vorname + "%");
        if (ahvNummer != null) query.setParameter("ahv", ahvNummer.getValue());
        if (geburtsdatum != null) query.setParameter("geburtsdatum", geburtsdatum);

        return query.getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void delete(String personId) {
        PersonEntity entity = em.find(PersonEntity.class, personId);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    public boolean existsByAhvNummer(AhvNummer ahvNummer) {
        Long count = em.createQuery(
                "SELECT COUNT(p) FROM PersonEntity p WHERE p.ahvNummer = :ahv", Long.class)
                .setParameter("ahv", ahvNummer.getValue())
                .getSingleResult();
        return count > 0;
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private PersonEntity toEntity(Person person) {
        PersonEntity e = new PersonEntity();
        e.setPersonId(person.getPersonId());
        e.setName(person.getName());
        e.setVorname(person.getVorname());
        e.setGeschlecht(person.getGeschlecht().name());
        e.setGeburtsdatum(person.getGeburtsdatum());
        e.setAhvNummer(person.getAhvNummer() != null ? person.getAhvNummer().getValue() : null);
        for (Adresse a : person.getAdressen()) {
            AdresseEntity ae = toAdresseEntity(a, e);
            e.getAdressen().add(ae);
        }
        return e;
    }

    private void updateEntity(PersonEntity e, Person person) {
        e.setName(person.getName());
        e.setVorname(person.getVorname());
        e.setGeschlecht(person.getGeschlecht().name());
        e.setGeburtsdatum(person.getGeburtsdatum());

        // sync adressen: remove deleted, update existing, add new
        e.getAdressen().removeIf(ae ->
                person.getAdressen().stream().noneMatch(a -> a.getAdressId().equals(ae.getAdressId())));
        for (Adresse a : person.getAdressen()) {
            AdresseEntity existing = e.getAdressen().stream()
                    .filter(ae -> ae.getAdressId().equals(a.getAdressId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.setGueltigVon(a.getGueltigVon());
                existing.setGueltigBis(a.getGueltigBis());
                existing.setStrasse(a.getStrasse());
                existing.setHausnummer(a.getHausnummer());
                existing.setPlz(a.getPlz());
                existing.setOrt(a.getOrt());
                existing.setLand(a.getLand());
            } else {
                e.getAdressen().add(toAdresseEntity(a, e));
            }
        }
    }

    private AdresseEntity toAdresseEntity(Adresse a, PersonEntity personEntity) {
        AdresseEntity ae = new AdresseEntity();
        ae.setAdressId(a.getAdressId());
        ae.setPerson(personEntity);
        ae.setAdressTyp(a.getAdressTyp().name());
        ae.setStrasse(a.getStrasse());
        ae.setHausnummer(a.getHausnummer());
        ae.setPlz(a.getPlz());
        ae.setOrt(a.getOrt());
        ae.setLand(a.getLand());
        ae.setGueltigVon(a.getGueltigVon());
        ae.setGueltigBis(a.getGueltigBis());
        return ae;
    }

    private Person toDomain(PersonEntity e) {
        Person person = new Person(
                e.getPersonId(),
                e.getName(),
                e.getVorname(),
                Geschlecht.valueOf(e.getGeschlecht()),
                e.getGeburtsdatum(),
                e.getAhvNummer() != null ? new AhvNummer(e.getAhvNummer()) : null
        );
        List<Adresse> adressen = new ArrayList<>();
        for (AdresseEntity ae : e.getAdressen()) {
            adressen.add(new Adresse(
                    ae.getAdressId(),
                    e.getPersonId(),
                    AdressTyp.valueOf(ae.getAdressTyp()),
                    ae.getStrasse(),
                    ae.getHausnummer(),
                    ae.getPlz(),
                    ae.getOrt(),
                    ae.getLand(),
                    ae.getGueltigVon(),
                    ae.getGueltigBis()
            ));
        }
        person.setAdressen(adressen);
        return person;
    }
}
