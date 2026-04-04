package ch.yuno.partner.domain.port.in;

import ch.yuno.partner.domain.model.Address;
import ch.yuno.partner.domain.model.PageRequest;
import ch.yuno.partner.domain.model.PageResult;
import ch.yuno.partner.domain.model.Person;
import ch.yuno.partner.domain.model.PersonId;

import java.time.LocalDate;
import java.util.List;

/**
 * Inbound port for person query use cases.
 */
public interface PersonQueryUseCase {

    Person findById(PersonId personId);

    List<Person> listAllPersons();

    List<Person> searchPersons(String name, String firstName, String socialSecurityNumberRaw, LocalDate dateOfBirth);

    PageResult<Person> searchPersons(String name, String firstName, String socialSecurityNumberRaw, LocalDate dateOfBirth, PageRequest pageRequest);

    List<Address> getAddresses(PersonId personId);
}
