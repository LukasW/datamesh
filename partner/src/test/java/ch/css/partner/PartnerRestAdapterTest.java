package ch.css.partner;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration Tests for Person REST API.
 * Kafka publishing is replaced by Debezium CDC – no Kafka broker needed.
 * Outbox writes are verified via direct EntityManager queries.
 */
@QuarkusTest
class PersonRestAdapterTest {

    @Inject
    EntityManager em;

    private static final String VALID_AHV = "756.1234.5678.97";

    @Test
    @DisplayName("POST /api/personen – Person erstellen")
    void testCreatePerson() {
        given()
                .contentType(ContentType.JSON)
                .body(personPayload("Muster", "Hans", VALID_AHV))
                .when().post("/api/personen")
                .then()
                .statusCode(201)
                .body("id", notNullValue());
    }

    @Test
    @DisplayName("POST /api/personen – ungültige AHV-Nummer → 400")
    void testCreatePersonInvalidAhv() {
        given()
                .contentType(ContentType.JSON)
                .body(personPayload("Muster", "Hans", "756.0000.0000.00"))
                .when().post("/api/personen")
                .then()
                .statusCode(400)
                .body("message", containsString("check digit"));
    }

    @Test
    @DisplayName("GET /api/personen/{id} – Person abrufen")
    void testGetPerson() {
        String id = createTestPerson("Müller", "Anna", "756.9217.0769.85");

        given()
                .when().get("/api/personen/" + id)
                .then()
                .statusCode(200)
                .body("personId", equalTo(id))
                .body("name", equalTo("Müller"))
                .body("firstName", equalTo("Anna"))
                .body("socialSecurityNumber", equalTo("756.9217.0769.85"));
    }

    @Test
    @DisplayName("GET /api/personen/{id} – 404 für unbekannte ID")
    void testGetPersonNotFound() {
        given()
                .when().get("/api/personen/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("PUT /api/personen/{id} – Personalien aktualisieren")
    void testUpdatePerson() {
        String id = createTestPerson("Alt", "Name", "756.3456.7890.02");

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Neu\",\"firstName\":\"Name\",\"gender\":\"FEMALE\",\"dateOfBirth\":\"1990-01-01\"}")
                .when().put("/api/personen/" + id)
                .then()
                .statusCode(200)
                .body("name", equalTo("Neu"));
    }

    @Test
    @DisplayName("DELETE /api/personen/{id} – Person löschen")
    void testDeletePerson() {
        String id = createTestPerson("Loeschen", "Test", "756.8754.4321.86");
        given().when().delete("/api/personen/" + id).then().statusCode(204);
        given().when().get("/api/personen/" + id).then().statusCode(404);
    }

    @Test
    @DisplayName("POST /api/personen/{id}/adressen – Adresse hinzufügen")
    void testAddAdresse() {
        String personId = createTestPerson("Adress", "Test", "756.2984.7562.72");

        String adressePayload = """
                {
                  "addressType": "RESIDENCE",
                  "street": "Musterstrasse",
                  "houseNumber": "1",
                  "postalCode": "8001",
                  "city": "Zürich",
                  "land": "Schweiz",
                  "validFrom": "2020-01-01"
                }
                """;
        given()
                .contentType(ContentType.JSON)
                .body(adressePayload)
                .when().post("/api/personen/" + personId + "/adressen")
                .then()
                .statusCode(201)
                .body("addressId", notNullValue());
    }

    @Test
    @DisplayName("POST /api/personen/{id}/adressen – Überschneidung → erste Adresse wird automatisch zugeschnitten")
    void testAddAdresseUeberschneidung() {
        String personId = createTestPerson("Overlap", "Test", "756.5432.1987.61");

        String ersteAdresse = """
                {"addressType":"RESIDENCE","street":"Str","houseNumber":"1",
                 "postalCode":"8001","city":"Zürich","land":"Schweiz","validFrom":"2020-01-01"}
                """;
        String ersteAdressId = given().contentType(ContentType.JSON).body(ersteAdresse)
                .when().post("/api/personen/" + personId + "/adressen")
                .then().statusCode(201)
                .extract().path("addressId");

        String zweiteAdresse = """
                {"addressType":"RESIDENCE","street":"Neue Str","houseNumber":"2",
                 "postalCode":"3000","city":"Bern","land":"Schweiz","validFrom":"2022-01-01"}
                """;
        given().contentType(ContentType.JSON).body(zweiteAdresse)
                .when().post("/api/personen/" + personId + "/adressen")
                .then().statusCode(201);

        given().when().get("/api/personen/" + personId + "/adressen")
                .then().statusCode(200)
                .body("find { it.addressId == '" + ersteAdressId + "' }.validTo",
                        equalTo("2021-12-31"));
    }

    @Test
    @DisplayName("DELETE /api/personen/{id}/adressen/{aid} – Adresse löschen")
    void testDeleteAdresse() {
        String personId = createTestPerson("DelAddr", "Test", "756.7654.3219.89");

        String adresse = """
                {"addressType":"CORRESPONDENCE","street":"Str","houseNumber":"2",
                 "postalCode":"3000","city":"Bern","land":"Schweiz","validFrom":"2022-01-01"}
                """;
        String adressId = given()
                .contentType(ContentType.JSON).body(adresse)
                .when().post("/api/personen/" + personId + "/adressen")
                .then().statusCode(201)
                .extract().path("addressId");

        given().when().delete("/api/personen/" + personId + "/adressen/" + adressId)
                .then().statusCode(204);
    }

    @Test
    @DisplayName("POST /api/personen – PersonCreated-Eintrag wird in Outbox geschrieben")
    @Transactional
    void testCreatePerson_writesPersonCreatedToOutbox() {
        long countBefore = outboxCount("PersonCreated");

        given()
                .contentType(ContentType.JSON)
                .body(personPayload("OutboxTest", "User", "756.4444.5555.60"))
                .when().post("/api/personen")
                .then().statusCode(201);

        assertTrue(outboxCount("PersonCreated") > countBefore,
                "Expected a PersonCreated entry in the outbox table");
    }

    @Test
    @DisplayName("DELETE /api/personen/{id} – PersonDeleted-Eintrag wird in Outbox geschrieben")
    @Transactional
    void testDeletePerson_writesPersonDeletedToOutbox() {
        String id = createTestPerson("OutboxDel", "Test", "756.6543.2198.73");
        long countBefore = outboxCount("PersonDeleted");

        given().when().delete("/api/personen/" + id).then().statusCode(204);

        assertTrue(outboxCount("PersonDeleted") > countBefore,
                "Expected a PersonDeleted entry in the outbox table");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String createTestPerson(String name, String firstName, String ahv) {
        return given()
                .contentType(ContentType.JSON)
                .body(personPayload(name, firstName, ahv))
                .when().post("/api/personen")
                .then().statusCode(201)
                .extract().path("id");
    }

    private String personPayload(String name, String firstName, String ahv) {
        return String.format(
                "{\"name\":\"%s\",\"firstName\":\"%s\",\"gender\":\"MALE\"," +
                "\"dateOfBirth\":\"1980-05-12\",\"socialSecurityNumber\":\"%s\"}",
                name, firstName, ahv);
    }

    private long outboxCount(String eventType) {
        return em.createQuery(
                "SELECT COUNT(o) FROM OutboxEntity o WHERE o.eventType = :type", Long.class)
                .setParameter("type", eventType)
                .getSingleResult();
    }
}
