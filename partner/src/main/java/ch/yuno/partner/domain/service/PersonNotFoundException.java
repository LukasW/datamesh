package ch.yuno.partner.domain.service;

public class PersonNotFoundException extends RuntimeException {
    public PersonNotFoundException(String personId) {
        super("Person not found: " + personId);
    }
}
