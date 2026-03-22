package ch.yuno.partner.domain.model.events;

import java.time.LocalDate;

public record PersonCreatedEvent(
    String personId,
    String name,
    String firstName,
    String socialSecurityNumber,
    LocalDate dateOfBirth
) {}
