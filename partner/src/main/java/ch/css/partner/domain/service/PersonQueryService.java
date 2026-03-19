package ch.css.partner.domain.service;

import ch.css.partner.domain.model.Address;
import ch.css.partner.domain.model.PageRequest;
import ch.css.partner.domain.model.PageResult;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.model.SocialSecurityNumber;
import ch.css.partner.domain.port.out.PersonRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class PersonQueryService {

    @Inject
    PersonRepository personRepository;

    public Person findById(String personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException(personId));
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

    public PageResult<Person> searchPersons(String name, String firstName, String socialSecurityNumberRaw, LocalDate dateOfBirth, PageRequest pageRequest) {
        SocialSecurityNumber socialSecurityNumber = (socialSecurityNumberRaw != null && !socialSecurityNumberRaw.isBlank())
                ? new SocialSecurityNumber(socialSecurityNumberRaw) : null;
        return personRepository.search(name, firstName, socialSecurityNumber, dateOfBirth, pageRequest);
    }

    public List<Address> getAddresses(String personId) {
        return findById(personId).getAddresses();
    }
}
