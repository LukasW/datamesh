package ch.css.partner;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * Integration Tests for Person REST API
 */
@QuarkusTest
class PersonRestAdapterTest {

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
                .body("message", containsString("Prüfziffer"));
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
                .body("vorname", equalTo("Anna"))
                .body("ahvNummer", equalTo("756.9217.0769.85"));
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
                .body("{\"name\":\"Neu\",\"vorname\":\"Name\",\"geschlecht\":\"WEIBLICH\",\"geburtsdatum\":\"1990-01-01\"}")
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
                  "adressTyp": "WOHNADRESSE",
                  "strasse": "Musterstrasse",
                  "hausnummer": "1",
                  "plz": "8001",
                  "ort": "Zürich",
                  "land": "Schweiz",
                  "gueltigVon": "2020-01-01"
                }
                """;
        given()
                .contentType(ContentType.JSON)
                .body(adressePayload)
                .when().post("/api/personen/" + personId + "/adressen")
                .then()
                .statusCode(201)
                .body("adressId", notNullValue());
    }

    @Test
    @DisplayName("POST /api/personen/{id}/adressen – Überschneidung → erste Adresse wird automatisch zugeschnitten")
    void testAddAdresseUeberschneidung() {
        String personId = createTestPerson("Overlap", "Test", "756.5432.1987.61");

        String ersteAdresse = """
                {"adressTyp":"WOHNADRESSE","strasse":"Str","hausnummer":"1",
                 "plz":"8001","ort":"Zürich","land":"Schweiz","gueltigVon":"2020-01-01"}
                """;
        String ersteAdressId = given().contentType(ContentType.JSON).body(ersteAdresse)
                .when().post("/api/personen/" + personId + "/adressen")
                .then().statusCode(201)
                .extract().path("adressId");

        String zweiteAdresse = """
                {"adressTyp":"WOHNADRESSE","strasse":"Neue Str","hausnummer":"2",
                 "plz":"3000","ort":"Bern","land":"Schweiz","gueltigVon":"2022-01-01"}
                """;
        // Zweite Adresse ab 2022 → erste wird automatisch auf 2021-12-31 zugeschnitten
        given().contentType(ContentType.JSON).body(zweiteAdresse)
                .when().post("/api/personen/" + personId + "/adressen")
                .then().statusCode(201);

        // Erste Adresse prüfen: gueltigBis muss 2021-12-31 sein
        given().when().get("/api/personen/" + personId + "/adressen")
                .then().statusCode(200)
                .body("find { it.adressId == '" + ersteAdressId + "' }.gueltigBis",
                        equalTo("2021-12-31"));
    }

    @Test
    @DisplayName("DELETE /api/personen/{id}/adressen/{aid} – Adresse löschen")
    void testDeleteAdresse() {
        String personId = createTestPerson("DelAddr", "Test", "756.7654.3219.89");

        String adresse = """
                {"adressTyp":"KORRESPONDENZADRESSE","strasse":"Str","hausnummer":"2",
                 "plz":"3000","ort":"Bern","land":"Schweiz","gueltigVon":"2022-01-01"}
                """;
        String adressId = given()
                .contentType(ContentType.JSON).body(adresse)
                .when().post("/api/personen/" + personId + "/adressen")
                .then().statusCode(201)
                .extract().path("adressId");

        given().when().delete("/api/personen/" + personId + "/adressen/" + adressId)
                .then().statusCode(204);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String createTestPerson(String name, String vorname, String ahv) {
        return given()
                .contentType(ContentType.JSON)
                .body(personPayload(name, vorname, ahv))
                .when().post("/api/personen")
                .then().statusCode(201)
                .extract().path("id");
    }

    private String personPayload(String name, String vorname, String ahv) {
        return String.format(
                "{\"name\":\"%s\",\"vorname\":\"%s\",\"geschlecht\":\"MAENNLICH\"," +
                "\"geburtsdatum\":\"1980-05-12\",\"ahvNummer\":\"%s\"}",
                name, vorname, ahv);
    }
}
