package ch.yuno.hrintegration.processor;

import ch.yuno.hrintegration.model.OrgUnitEvent;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Instant;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Transforms an OData OrganizationUnit JSON entity into Kafka event records.
 */
@ApplicationScoped
@Named("orgUnitEventProcessor")
public class OrgUnitEventProcessor {

    public OrgUnitEvent.State toStateEvent(JsonNode node) {
        return new OrgUnitEvent.State(
            textOrNull(node, "orgUnitId"),
            textOrNull(node, "orgUnitId"),
            textOrNull(node, "name"),
            textOrNull(node, "parentOrgUnitId"),
            textOrNull(node, "managerEmployeeId"),
            intOrDefault(node, "level", 1),
            boolOrDefault(node, "active", true),
            false,
            longOrDefault(node, "version", 1),
            Instant.now()
        );
    }

    public OrgUnitEvent.Changed toChangedEvent(JsonNode node) {
        var orgUnitId = textOrNull(node, "orgUnitId");
        var version = longOrDefault(node, "version", 1);
        return new OrgUnitEvent.Changed(
            generateDeterministicEventId(orgUnitId, version),
            "org-unit.updated",
            orgUnitId,
            textOrNull(node, "orgUnitId"),
            Instant.now()
        );
    }

    UUID generateDeterministicEventId(String orgUnitId, long version) {
        return UUID.nameUUIDFromBytes((orgUnitId + ":" + version).getBytes(UTF_8));
    }

    private String textOrNull(JsonNode node, String field) {
        var child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private int intOrDefault(JsonNode node, String field, int defaultValue) {
        var child = node.get(field);
        return (child != null && !child.isNull()) ? child.asInt() : defaultValue;
    }

    private boolean boolOrDefault(JsonNode node, String field, boolean defaultValue) {
        var child = node.get(field);
        return (child != null && !child.isNull()) ? child.asBoolean() : defaultValue;
    }

    private long longOrDefault(JsonNode node, String field, long defaultValue) {
        var child = node.get(field);
        return (child != null && !child.isNull()) ? child.asLong() : defaultValue;
    }
}
