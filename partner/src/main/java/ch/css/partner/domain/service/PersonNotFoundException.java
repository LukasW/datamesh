package ch.css.partner.domain.service;

public class PersonNotFoundException extends RuntimeException {
    public PersonNotFoundException(String personId) {
        super("Person nicht gefunden: " + personId);
    }
}
