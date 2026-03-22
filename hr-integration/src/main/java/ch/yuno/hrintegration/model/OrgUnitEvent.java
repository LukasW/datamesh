package ch.yuno.hrintegration.model;

import java.time.Instant;
import java.util.UUID;

public sealed interface OrgUnitEvent {

    record State(
        String orgUnitId,
        String externalId,
        String name,
        String parentOrgUnitId,
        String managerEmployeeId,
        int level,
        boolean active,
        boolean deleted,
        long version,
        Instant timestamp
    ) implements OrgUnitEvent {}

    record Changed(
        UUID eventId,
        String eventType,
        String orgUnitId,
        String externalId,
        Instant timestamp
    ) implements OrgUnitEvent {}
}
