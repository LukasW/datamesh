package ch.yuno.partner.integration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("Partner - Flyway Migration Integration Test")
class PersonFlywayMigrationIT {

    @Inject
    EntityManager em;

    @Test
    @DisplayName("All tables and indices created by Flyway migrations")
    void allTablesExist() {
        // Verify person table exists
        var result = em.createNativeQuery(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'person'")
            .getSingleResult();
        assertEquals(1L, ((Number) result).longValue(), "person table should exist");

        // Verify address table exists
        result = em.createNativeQuery(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'address'")
            .getSingleResult();
        assertEquals(1L, ((Number) result).longValue(), "address table should exist");

        // Verify outbox table exists
        result = em.createNativeQuery(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'outbox'")
            .getSingleResult();
        assertEquals(1L, ((Number) result).longValue(), "outbox table should exist");
    }

    @Test
    @DisplayName("Flyway schema history table is populated")
    void flywayHistoryPopulated() {
        var result = em.createNativeQuery(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")
            .getSingleResult();
        assertTrue(((Number) result).longValue() >= 1,
            "At least one successful Flyway migration should be recorded");
    }
}
