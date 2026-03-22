package ch.yuno.policy.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@DisplayName("Policy - REST Adapter Integration Test")
class PolicyRestAdapterIT {

    @Test
    @DisplayName("POST /api/policies creates a policy and returns 201")
    void createPolicy_returns201() {
        String policyId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "partnerId": "partner-rest-test",
                    "productId": "product-rest-test",
                    "coverageStartDate": "2026-01-01",
                    "coverageEndDate": "2027-01-01",
                    "premium": 500.00,
                    "deductible": 200.00
                }""")
        .when()
            .post("/api/policies")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .extract().jsonPath().getString("id");

        assertNotNull(policyId);

        // GET the created policy
        given()
        .when()
            .get("/api/policies/" + policyId)
        .then()
            .statusCode(200)
            .body("partnerId", equalTo("partner-rest-test"))
            .body("productId", equalTo("product-rest-test"))
            .body("status", equalTo("DRAFT"));
    }

    @Test
    @DisplayName("GET /api/policies/{id} with unknown ID returns 404")
    void getPolicy_unknownId_returns404() {
        given()
        .when()
            .get("/api/policies/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404);
    }

    @Test
    @DisplayName("POST /api/policies/{id}/activate activates a DRAFT policy")
    void activatePolicy_returns200() {
        // Create a DRAFT policy
        String policyId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "partnerId": "partner-activate",
                    "productId": "product-activate",
                    "coverageStartDate": "2026-01-01",
                    "premium": 300.00,
                    "deductible": 0
                }""")
        .when()
            .post("/api/policies")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("id");

        // Activate the policy
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/policies/" + policyId + "/activate")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));
    }

    @Test
    @DisplayName("POST /api/policies/{id}/cancel cancels an ACTIVE policy")
    void cancelPolicy_returns200() {
        // Create and activate a policy
        String policyId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "partnerId": "partner-cancel",
                    "productId": "product-cancel",
                    "coverageStartDate": "2026-01-01",
                    "premium": 300.00,
                    "deductible": 50.00
                }""")
        .when()
            .post("/api/policies")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("id");

        given().contentType(ContentType.JSON).post("/api/policies/" + policyId + "/activate").then().statusCode(200);

        // Cancel the policy
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/policies/" + policyId + "/cancel")
        .then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/policies/{id}/coverages adds a coverage")
    void addCoverage_returns201() {
        // Create a policy
        String policyId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "partnerId": "partner-coverage",
                    "productId": "product-coverage",
                    "coverageStartDate": "2026-01-01",
                    "premium": 400.00,
                    "deductible": 100.00
                }""")
        .when()
            .post("/api/policies")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("id");

        // Add a coverage
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "coverageType": "LIABILITY",
                    "insuredAmount": 100000.00
                }""")
        .when()
            .post("/api/policies/" + policyId + "/coverages")
        .then()
            .statusCode(201)
            .body("coverageType", equalTo("LIABILITY"))
            .body("coverageId", notNullValue());
    }

    @Test
    @DisplayName("POST /api/policies with missing required fields returns 400")
    void createPolicy_missingFields_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "partnerId": "",
                    "productId": "product",
                    "coverageStartDate": "2026-01-01",
                    "premium": 100.00,
                    "deductible": 0
                }""")
        .when()
            .post("/api/policies")
        .then()
            .statusCode(400);
    }
}
