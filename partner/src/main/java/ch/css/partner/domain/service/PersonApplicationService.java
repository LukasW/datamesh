package ch.css.partner.domain.service;

import ch.css.partner.domain.model.Adresse;
import ch.css.partner.domain.model.AdressTyp;
import ch.css.partner.domain.model.AhvNummer;
import ch.css.partner.domain.model.Geschlecht;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.port.out.PersonEventPublisher;
import ch.css.partner.domain.port.out.PersonRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class PersonApplicationService {

    @Inject
    PersonRepository personRepository;

    @Inject
    PersonEventPublisher personEventPublisher;

    // ── Personenverwaltung ────────────────────────────────────────────────────

    @Transactional
    public String createPerson(String name, String vorname, Geschlecht geschlecht,
                               LocalDate geburtsdatum, String ahvNummerRaw) {
        AhvNummer ahvNummer = null;
        if (ahvNummerRaw != null && !ahvNummerRaw.isBlank()) {
            ahvNummer = new AhvNummer(ahvNummerRaw); // throws on invalid format
            if (personRepository.existsByAhvNummer(ahvNummer)) {
                throw new IllegalArgumentException("AHV-Nummer bereits vorhanden: " + ahvNummer.formatted());
            }
        }
        Person person = new Person(name, vorname, geschlecht, geburtsdatum, ahvNummer);
        personRepository.save(person);
        personEventPublisher.publishPersonErstellt(person.getPersonId(), name, vorname, ahvNummer, geburtsdatum);
        return person.getPersonId();
    }

    @Transactional
    public void updatePersonalien(String personId, String name, String vorname,
                                  Geschlecht geschlecht, LocalDate geburtsdatum) {
        Person person = findOrThrow(personId);
        person.updatePersonalien(name, vorname, geschlecht, geburtsdatum);
        personRepository.save(person);
        personEventPublisher.publishPersonAktualisiert(personId, name, vorname);
    }

    @Transactional
    public void deletePerson(String personId) {
        findOrThrow(personId);
        personRepository.delete(personId);
        personEventPublisher.publishPersonGeloescht(personId);
    }

    public Person findById(String personId) {
        return findOrThrow(personId);
    }

    public List<Person> listAllPersonen() {
        return personRepository.search(null, null, null, null);
    }

    public List<Person> searchPersonen(String name, String vorname, String ahvNummerRaw, LocalDate geburtsdatum) {
        boolean hasFilter = (name != null && !name.isBlank())
                || (vorname != null && !vorname.isBlank())
                || (ahvNummerRaw != null && !ahvNummerRaw.isBlank())
                || geburtsdatum != null;
        if (!hasFilter) {
            throw new IllegalArgumentException("Mindestens ein Suchfeld muss befüllt sein");
        }
        AhvNummer ahvNummer = (ahvNummerRaw != null && !ahvNummerRaw.isBlank())
                ? new AhvNummer(ahvNummerRaw) : null;
        return personRepository.search(name, vorname, ahvNummer, geburtsdatum);
    }

    // ── Adressverwaltung ─────────────────────────────────────────────────────

    @Transactional
    public String addAdresse(String personId, AdressTyp adressTyp,
                             String strasse, String hausnummer, String plz, String ort, String land,
                             LocalDate gueltigVon, LocalDate gueltigBis) {
        Person person = findOrThrow(personId);
        String adressId = person.addAdresse(adressTyp, strasse, hausnummer, plz, ort, land, gueltigVon, gueltigBis);
        personRepository.save(person);
        personEventPublisher.publishAdresseHinzugefuegt(personId, adressId, adressTyp, gueltigVon);
        return adressId;
    }

    @Transactional
    public void updateAdressGueltigkeit(String personId, String adressId,
                                        LocalDate gueltigVon, LocalDate gueltigBis) {
        Person person = findOrThrow(personId);
        person.updateAdressGueltigkeit(adressId, gueltigVon, gueltigBis);
        personRepository.save(person);
        personEventPublisher.publishAdresseAktualisiert(personId, adressId, gueltigVon, gueltigBis);
    }

    @Transactional
    public void deleteAdresse(String personId, String adressId) {
        Person person = findOrThrow(personId);
        person.removeAdresse(adressId);
        personRepository.save(person);
    }

    public List<Adresse> getAdressen(String personId) {
        return findOrThrow(personId).getAdressen();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Person findOrThrow(String personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException(personId));
    }
}
