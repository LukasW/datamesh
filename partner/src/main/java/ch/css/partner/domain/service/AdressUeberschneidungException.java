package ch.css.partner.domain.service;

import ch.css.partner.domain.model.AdressTyp;

import java.time.LocalDate;

public class AdressUeberschneidungException extends RuntimeException {
    public AdressUeberschneidungException(AdressTyp adressTyp, LocalDate vonNeu, LocalDate bisNeu,
                                           LocalDate vonBestehend, LocalDate bisBestehend) {
        super(String.format(
                "Für Adresstyp %s existiert bereits eine Adresse im Zeitraum %s – %s, " +
                "die sich mit %s – %s überschneidet",
                adressTyp,
                vonBestehend, bisBestehend != null ? bisBestehend : "∞",
                vonNeu, bisNeu != null ? bisNeu : "∞"
        ));
    }
}
