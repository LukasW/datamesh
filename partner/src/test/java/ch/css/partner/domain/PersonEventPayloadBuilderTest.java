package ch.css.partner.domain;

import ch.css.partner.domain.model.AddressType;
import ch.css.partner.domain.model.SocialSecurityNumber;
import ch.css.partner.domain.service.PersonEventPayloadBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PersonEventPayloadBuilder – JSON Payload Tests")
class PersonEventPayloadBuilderTest {

    private static final String PERSON_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String ADDRESS_ID = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final SocialSecurityNumber SSN = new SocialSecurityNumber("756.1234.5678.97");

    // ── PersonCreated ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildPersonCreated – enthält alle Pflichtfelder")
    void buildPersonCreated_containsRequiredFields() {
        String json = PersonEventPayloadBuilder.buildPersonCreated(
                PERSON_ID, "Muster", "Hans", SSN, LocalDate.of(1980, 5, 12));

        assertTrue(json.contains("\"eventType\":\"PersonCreated\""));
        assertTrue(json.contains("\"personId\":\"" + PERSON_ID + "\""));
        assertTrue(json.contains("\"name\":\"Muster\""));
        assertTrue(json.contains("\"firstName\":\"Hans\""));
        assertTrue(json.contains("\"socialSecurityNumber\":\"756.1234.5678.97\""));
        assertTrue(json.contains("\"dateOfBirth\":\"1980-05-12\""));
        assertTrue(json.contains("\"eventId\":\""));
        assertTrue(json.contains("\"timestamp\":\""));
    }

    @Test
    @DisplayName("buildPersonCreated – AHV null → socialSecurityNumber: null im JSON")
    void buildPersonCreated_nullSsn_producesJsonNull() {
        String json = PersonEventPayloadBuilder.buildPersonCreated(
                PERSON_ID, "Muster", "Hans", null, LocalDate.of(1980, 5, 12));

        assertTrue(json.contains("\"socialSecurityNumber\":null"));
        assertFalse(json.contains("\"socialSecurityNumber\":\"null\""));
    }

    @Test
    @DisplayName("buildPersonCreated – eventId ist eindeutig pro Aufruf")
    void buildPersonCreated_uniqueEventId() {
        String json1 = PersonEventPayloadBuilder.buildPersonCreated(
                PERSON_ID, "Muster", "Hans", null, LocalDate.of(1980, 5, 12));
        String json2 = PersonEventPayloadBuilder.buildPersonCreated(
                PERSON_ID, "Muster", "Hans", null, LocalDate.of(1980, 5, 12));

        assertNotEquals(json1, json2);
    }

    // ── PersonUpdated ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildPersonUpdated – enthält alle Pflichtfelder")
    void buildPersonUpdated_containsRequiredFields() {
        String json = PersonEventPayloadBuilder.buildPersonUpdated(PERSON_ID, "Neu", "Name");

        assertTrue(json.contains("\"eventType\":\"PersonUpdated\""));
        assertTrue(json.contains("\"personId\":\"" + PERSON_ID + "\""));
        assertTrue(json.contains("\"name\":\"Neu\""));
        assertTrue(json.contains("\"firstName\":\"Name\""));
        assertTrue(json.contains("\"timestamp\":\""));
    }

    // ── PersonDeleted ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildPersonDeleted – enthält alle Pflichtfelder")
    void buildPersonDeleted_containsRequiredFields() {
        String json = PersonEventPayloadBuilder.buildPersonDeleted(PERSON_ID);

        assertTrue(json.contains("\"eventType\":\"PersonDeleted\""));
        assertTrue(json.contains("\"personId\":\"" + PERSON_ID + "\""));
        assertTrue(json.contains("\"timestamp\":\""));
    }

    // ── AddressAdded ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildAddressAdded – enthält alle Pflichtfelder")
    void buildAddressAdded_containsRequiredFields() {
        String json = PersonEventPayloadBuilder.buildAddressAdded(
                PERSON_ID, ADDRESS_ID, AddressType.RESIDENCE, LocalDate.of(2020, 1, 1));

        assertTrue(json.contains("\"eventType\":\"AddressAdded\""));
        assertTrue(json.contains("\"personId\":\"" + PERSON_ID + "\""));
        assertTrue(json.contains("\"addressId\":\"" + ADDRESS_ID + "\""));
        assertTrue(json.contains("\"addressType\":\"RESIDENCE\""));
        assertTrue(json.contains("\"validFrom\":\"2020-01-01\""));
        assertTrue(json.contains("\"timestamp\":\""));
    }

    @Test
    @DisplayName("buildAddressAdded – addressType-Enum wird korrekt serialisiert")
    void buildAddressAdded_allAddressTypes() {
        assertTrue(PersonEventPayloadBuilder.buildAddressAdded(
                PERSON_ID, ADDRESS_ID, AddressType.CORRESPONDENCE, LocalDate.now())
                .contains("\"addressType\":\"CORRESPONDENCE\""));
        assertTrue(PersonEventPayloadBuilder.buildAddressAdded(
                PERSON_ID, ADDRESS_ID, AddressType.DELIVERY, LocalDate.now())
                .contains("\"addressType\":\"DELIVERY\""));
    }

    // ── AddressUpdated ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildAddressUpdated – validTo vorhanden → Datumsstring im JSON")
    void buildAddressUpdated_withValidTo() {
        String json = PersonEventPayloadBuilder.buildAddressUpdated(
                PERSON_ID, ADDRESS_ID,
                LocalDate.of(2020, 1, 1), LocalDate.of(2022, 12, 31));

        assertTrue(json.contains("\"eventType\":\"AddressUpdated\""));
        assertTrue(json.contains("\"validFrom\":\"2020-01-01\""));
        assertTrue(json.contains("\"validTo\":\"2022-12-31\""));
    }

    @Test
    @DisplayName("buildAddressUpdated – validTo null → 'null' als String im JSON")
    void buildAddressUpdated_nullValidTo_producesNullString() {
        String json = PersonEventPayloadBuilder.buildAddressUpdated(
                PERSON_ID, ADDRESS_ID, LocalDate.of(2020, 1, 1), null);

        assertTrue(json.contains("\"validTo\":\"null\""));
    }

    // ── Topic-Konstanten ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Topic-Konstanten haben korrekten Wert")
    void topicConstants_haveCorrectValues() {
        assertEquals("person.v1.created",         PersonEventPayloadBuilder.TOPIC_PERSON_CREATED);
        assertEquals("person.v1.updated",         PersonEventPayloadBuilder.TOPIC_PERSON_UPDATED);
        assertEquals("person.v1.deleted",         PersonEventPayloadBuilder.TOPIC_PERSON_DELETED);
        assertEquals("person.v1.address-added",   PersonEventPayloadBuilder.TOPIC_PERSON_ADDRESS_ADDED);
        assertEquals("person.v1.address-updated", PersonEventPayloadBuilder.TOPIC_PERSON_ADDRESS_UPDATED);
    }
}
