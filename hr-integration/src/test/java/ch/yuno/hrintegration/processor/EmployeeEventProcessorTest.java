package ch.yuno.hrintegration.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmployeeEventProcessorTest {

    private final EmployeeEventProcessor processor = new EmployeeEventProcessor();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldTransformODataEntityToStateEvent() throws Exception {
        var json = """
            {
              "employeeId": "a1b2c3d4-0001-0001-0001-000000000001",
              "firstName": "Anna",
              "lastName": "Meier",
              "email": "anna.meier@yuno.ch",
              "jobTitle": "Schadensachbearbeiterin",
              "department": "Claims",
              "orgUnitId": "OU-CLM-001",
              "entryDate": "2023-06-01",
              "exitDate": null,
              "active": true,
              "version": 1
            }
            """;
        var node = mapper.readTree(json);

        var state = processor.toStateEvent(node);

        assertEquals("Anna", state.firstName());
        assertEquals("Meier", state.lastName());
        assertEquals("anna.meier@yuno.ch", state.email());
        assertEquals("Claims", state.department());
        assertEquals("OU-CLM-001", state.orgUnitId());
        assertTrue(state.active());
        assertFalse(state.deleted());
        assertEquals(1, state.version());
        assertNotNull(state.employeeId());
        assertNotNull(state.timestamp());
    }

    @Test
    void shouldTransformToChangedEvent() throws Exception {
        var json = """
            {
              "employeeId": "HR-EMP-001",
              "firstName": "Peter",
              "lastName": "Brunner",
              "version": 3
            }
            """;
        var node = mapper.readTree(json);

        var changed = processor.toChangedEvent(node);

        assertEquals("employee.updated", changed.eventType());
        assertEquals("HR-EMP-001", changed.externalId());
        assertNotNull(changed.eventId());
        assertNotNull(changed.employeeId());
        assertNotNull(changed.timestamp());
    }

    @Test
    void shouldGenerateDeterministicEventId() {
        var id1 = processor.generateDeterministicEventId("EMP-001", 5);
        var id2 = processor.generateDeterministicEventId("EMP-001", 5);
        assertEquals(id1, id2);

        var id3 = processor.generateDeterministicEventId("EMP-001", 6);
        assertNotEquals(id1, id3);
    }

    @Test
    void shouldGenerateDeterministicEmployeeId() throws Exception {
        var json1 = """
            {"employeeId": "HR-EMP-001", "firstName": "A", "lastName": "B", "entryDate": "2024-01-01", "version": 1}
            """;
        var json2 = """
            {"employeeId": "HR-EMP-001", "firstName": "C", "lastName": "D", "entryDate": "2024-01-01", "version": 2}
            """;

        var state1 = processor.toStateEvent(mapper.readTree(json1));
        var state2 = processor.toStateEvent(mapper.readTree(json2));

        // Same externalId → same internal employeeId
        assertEquals(state1.employeeId(), state2.employeeId());
    }

    @Test
    void shouldHandleNullFields() throws Exception {
        var json = """
            {
              "employeeId": "HR-MIN",
              "firstName": "Min",
              "lastName": "Data",
              "entryDate": "2024-01-01",
              "version": 1
            }
            """;
        var node = mapper.readTree(json);

        var state = processor.toStateEvent(node);

        assertNull(state.email());
        assertNull(state.jobTitle());
        assertNull(state.department());
        assertNull(state.orgUnitId());
        assertNull(state.exitDate());
        assertTrue(state.active()); // default
    }
}
