package ch.css.policy.domain.service;

public class DeckungNotFoundException extends RuntimeException {
    public DeckungNotFoundException(String deckungId) {
        super("Deckung nicht gefunden: " + deckungId);
    }
}
