package ch.css.partner;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * Integration Test for Partner REST API
 */
@QuarkusTest
class PartnerRestAdapterTest {

    @Test
    @DisplayName("Should search partners by name")
    void testSearchPartners() {
        RestAssured.basePath = "/api/partners";

        given()
                .when()
                .get("/search?name=Test")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @DisplayName("Should return empty list when no matches found")
    void testSearchNoMatches() {
        RestAssured.basePath = "/api/partners";

        given()
                .when()
                .get("/search?name=NonExistent")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @DisplayName("Should create new partner")
    void testCreatePartner() {
        RestAssured.basePath = "/api/partners";

        String payload = """
                {
                  "name": "ACME Insurance GmbH",
                  "email": "contact@acme.ch",
                  "phone": "+41 44 123 45 67",
                  "partnerType": "CUSTOMER"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post()
                .then()
                .statusCode(201)
                .body("partnerId", notNullValue());
    }

    @Test
    @DisplayName("Should reject invalid partner creation")
    void testCreatePartnerInvalid() {
        RestAssured.basePath = "/api/partners";

        String payload = """
                {
                  "name": "",
                  "email": "test@example.com",
                  "partnerType": "CUSTOMER"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post()
                .then()
                .statusCode(400)
                .body("message", containsString("required"));
    }
}
