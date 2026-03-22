package ch.yuno.hrintegration.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public sealed interface EmployeeEvent {

    record State(
        String employeeId,
        String externalId,
        String firstName,
        String lastName,
        String email,
        String jobTitle,
        String department,
        String orgUnitId,
        LocalDate entryDate,
        LocalDate exitDate,
        boolean active,
        boolean deleted,
        long version,
        Instant timestamp
    ) implements EmployeeEvent {}

    record Changed(
        UUID eventId,
        String eventType,
        String employeeId,
        String externalId,
        Instant timestamp
    ) implements EmployeeEvent {}
}
