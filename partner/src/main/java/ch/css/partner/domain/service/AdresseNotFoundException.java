package ch.css.partner.domain.service;

public class AdresseNotFoundException extends RuntimeException {
    public AdresseNotFoundException(String adressId) {
        super("Adresse nicht gefunden: " + adressId);
    }
}
