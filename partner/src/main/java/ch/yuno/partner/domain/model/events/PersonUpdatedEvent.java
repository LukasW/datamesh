package ch.yuno.partner.domain.model.events;

public record PersonUpdatedEvent(
    String personId,
    String name,
    String firstName
) {}
