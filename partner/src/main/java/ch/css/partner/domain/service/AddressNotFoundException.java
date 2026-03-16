package ch.css.partner.domain.service;

public class AddressNotFoundException extends RuntimeException {
    public AddressNotFoundException(String addressId) {
        super("Address not found: " + addressId);
    }
}
