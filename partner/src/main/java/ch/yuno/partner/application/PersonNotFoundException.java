package ch.yuno.partner.application;

public class PersonNotFoundException extends RuntimeException {
    public PersonNotFoundException(String personId) {
        super("Person not found: " + personId);
    }
}
