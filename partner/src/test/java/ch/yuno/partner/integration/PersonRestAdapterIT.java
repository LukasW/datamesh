package ch.yuno.partner.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@DisplayName("Partner - REST Adapter Integration Test")
class PersonRestAdapterIT {

    @Test
    @DisplayName("POST /api/persons creates a person and returns 201")
    void createPerson_returns201() {
        String body = """
            {
                "name": "Testperson",
                "firstName": "REST",
                "gender": "MALE",
                "dateOfBirth": "1985-06-15"
            }""";

        String personId = given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/persons")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .extract().jsonPath().getString("id");

        assertNotNull(personId);

        // GET the created person
        given()
        .when()
            .get("/api/persons/" + personId)
        .then()
            .statusCode(200)
            .body("name", equalTo("Testperson"))
            .body("firstName", equalTo("REST"))
            .body("gender", equalTo("MALE"));
    }

    @Test
    @DisplayName("GET /api/persons/{id} with unknown ID returns 404")
    void getPerson_unknownId_returns404() {
        given()
        .when()
            .get("/api/persons/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404);
    }

    @Test
    @DisplayName("PUT /api/persons/{id} updates an existing person")
    void updatePerson_returns200() {
        // Create a person first
        String personId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Original",
                    "firstName": "Name",
                    "gender": "FEMALE",
                    "dateOfBirth": "1990-03-20"
                }""")
        .when()
            .post("/api/persons")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("id");

        // Update the person
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Updated",
                    "firstName": "Person",
                    "gender": "FEMALE",
                    "dateOfBirth": "1990-03-20"
                }""")
        .when()
            .put("/api/persons/" + personId)
        .then()
            .statusCode(200)
            .body("name", equalTo("Updated"))
            .body("firstName", equalTo("Person"));
    }

    @Test
    @DisplayName("DELETE /api/persons/{id} removes an existing person")
    void deletePerson_returns204() {
        // Create a person first
        String personId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "ToDelete",
                    "firstName": "Person",
                    "gender": "MALE",
                    "dateOfBirth": "1988-11-11"
                }""")
        .when()
            .post("/api/persons")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("id");

        // Delete the person
        given()
        .when()
            .delete("/api/persons/" + personId)
        .then()
            .statusCode(204);

        // Verify it is gone
        given()
        .when()
            .get("/api/persons/" + personId)
        .then()
            .statusCode(404);
    }

    @Test
    @DisplayName("POST /api/persons with missing required fields returns 400")
    void createPerson_missingFields_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "",
                    "firstName": "Test",
                    "gender": "MALE",
                    "dateOfBirth": "1990-01-01"
                }""")
        .when()
            .post("/api/persons")
        .then()
            .statusCode(400);
    }
}
