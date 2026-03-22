package ch.yuno.partner.integration;

import ch.yuno.partner.domain.model.Gender;
import ch.yuno.partner.domain.model.PersonId;
import ch.yuno.partner.domain.service.PersonCommandService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("Partner - Outbox Roundtrip Integration Test")
class PersonOutboxRoundtripIT {

    @Inject
    PersonCommandService service;

    @Inject
    EntityManager em;

    @Test
    @DisplayName("Creating a person writes outbox entries to DB")
    void createPerson_writesOutboxEntries() {
        PersonId personId = service.createPerson("Integration", "Test", Gender.MALE,
                LocalDate.of(1990, 1, 1), null);

        assertNotNull(personId);

        // Check outbox entries were written (PersonCreated + PersonState = 2 entries)
        var outboxEntries = em.createNativeQuery(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = :id")
            .setParameter("id", personId.value())
            .getSingleResult();
        assertTrue(((Number) outboxEntries).longValue() >= 2,
            "At least two outbox entries (PersonCreated + PersonState) should exist for the new person");
    }

    @Test
    @DisplayName("Updating a person writes additional outbox entries")
    void updatePerson_writesOutboxEntries() {
        PersonId personId = service.createPerson("Update", "Test", Gender.FEMALE,
                LocalDate.of(1985, 6, 15), null);

        long countBefore = ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = :id")
            .setParameter("id", personId.value())
            .getSingleResult()).longValue();

        service.updatePersonalData(personId, "Updated", "Person", Gender.FEMALE,
                LocalDate.of(1985, 6, 15));

        long countAfter = ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = :id")
            .setParameter("id", personId.value())
            .getSingleResult()).longValue();

        assertTrue(countAfter > countBefore,
            "Updating a person should produce additional outbox entries");
    }
}
