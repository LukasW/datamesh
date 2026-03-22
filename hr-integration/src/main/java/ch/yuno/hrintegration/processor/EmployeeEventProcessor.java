package ch.yuno.hrintegration.processor;

import ch.yuno.hrintegration.model.EmployeeEvent;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Transforms an OData Employee JSON entity into Kafka event records.
 */
@ApplicationScoped
@Named("employeeEventProcessor")
public class EmployeeEventProcessor {

    public EmployeeEvent.State toStateEvent(JsonNode node) {
        var employeeId = resolveEmployeeId(node);
        return new EmployeeEvent.State(
            employeeId,
            textOrNull(node, "employeeId"),
            textOrNull(node, "firstName"),
            textOrNull(node, "lastName"),
            textOrNull(node, "email"),
            textOrNull(node, "jobTitle"),
            textOrNull(node, "department"),
            textOrNull(node, "orgUnitId"),
            parseDate(node, "entryDate"),
            parseDate(node, "exitDate"),
            boolOrDefault(node, "active", true),
            false,
            longOrDefault(node, "version", 1),
            Instant.now()
        );
    }

    public EmployeeEvent.Changed toChangedEvent(JsonNode node) {
        var employeeId = resolveEmployeeId(node);
        var version = longOrDefault(node, "version", 1);
        return new EmployeeEvent.Changed(
            generateDeterministicEventId(employeeId, version),
            "employee.updated",
            employeeId,
            textOrNull(node, "employeeId"),
            Instant.now()
        );
    }

    String resolveEmployeeId(JsonNode node) {
        var externalId = textOrNull(node, "employeeId");
        if (externalId != null) {
            return UUID.nameUUIDFromBytes(("hr-employee:" + externalId).getBytes(UTF_8)).toString();
        }
        return UUID.randomUUID().toString();
    }

    UUID generateDeterministicEventId(String employeeId, long version) {
        return UUID.nameUUIDFromBytes((employeeId + ":" + version).getBytes(UTF_8));
    }

    private String textOrNull(JsonNode node, String field) {
        var child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private LocalDate parseDate(JsonNode node, String field) {
        var text = textOrNull(node, field);
        return text != null ? LocalDate.parse(text) : null;
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
