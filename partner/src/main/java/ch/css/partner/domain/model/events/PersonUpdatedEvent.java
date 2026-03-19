package ch.css.partner.domain.model.events;

public record PersonUpdatedEvent(
    String personId,
    String name,
    String firstName
) {}
