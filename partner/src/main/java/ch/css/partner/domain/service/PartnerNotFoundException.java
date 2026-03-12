package ch.css.partner.domain.service;

/**
 * Domain Exception: Partner not found
 */
public class PartnerNotFoundException extends RuntimeException {

    public PartnerNotFoundException(String message) {
        super(message);
    }

    public PartnerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
