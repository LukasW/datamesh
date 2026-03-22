package ch.yuno.hrintegration.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrgUnitEventProcessorTest {

    private final OrgUnitEventProcessor processor = new OrgUnitEventProcessor();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldTransformOrgUnitToStateEvent() throws Exception {
        var json = """
            {
              "orgUnitId": "OU-CLM-001",
              "name": "Schadenabwicklung",
              "parentOrgUnitId": "OU-VER-001",
              "managerEmployeeId": "a1b2c3d4-0001-0001-0001-000000000001",
              "level": 3,
              "active": true,
              "version": 2
            }
            """;
        var node = mapper.readTree(json);

        var state = processor.toStateEvent(node);

        assertEquals("OU-CLM-001", state.orgUnitId());
        assertEquals("Schadenabwicklung", state.name());
        assertEquals("OU-VER-001", state.parentOrgUnitId());
        assertEquals(3, state.level());
        assertTrue(state.active());
        assertFalse(state.deleted());
        assertEquals(2, state.version());
    }

    @Test
    void shouldTransformToChangedEvent() throws Exception {
        var json = """
            {
              "orgUnitId": "OU-IT-001",
              "name": "IT & Daten",
              "version": 1
            }
            """;
        var node = mapper.readTree(json);

        var changed = processor.toChangedEvent(node);

        assertEquals("org-unit.updated", changed.eventType());
        assertEquals("OU-IT-001", changed.orgUnitId());
        assertNotNull(changed.eventId());
    }

    @Test
    void shouldGenerateDeterministicEventId() {
        var id1 = processor.generateDeterministicEventId("OU-001", 1);
        var id2 = processor.generateDeterministicEventId("OU-001", 1);
        assertEquals(id1, id2);

        var id3 = processor.generateDeterministicEventId("OU-001", 2);
        assertNotEquals(id1, id3);
    }

    @Test
    void shouldHandleNullOptionalFields() throws Exception {
        var json = """
            {
              "orgUnitId": "OU-ROOT",
              "name": "Yuno AG",
              "level": 1,
              "active": true,
              "version": 1
            }
            """;
        var node = mapper.readTree(json);

        var state = processor.toStateEvent(node);

        assertNull(state.parentOrgUnitId());
        assertNull(state.managerEmployeeId());
        assertEquals(1, state.level());
    }
}
