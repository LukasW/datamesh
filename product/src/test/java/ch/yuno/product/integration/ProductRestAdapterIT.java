package ch.yuno.product.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@DisplayName("Product - REST Adapter Integration Test")
class ProductRestAdapterIT {

    @Test
    @DisplayName("POST /api/products defines a product and returns 201")
    void defineProduct_returns201() {
        String productId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Hausrat Premium",
                    "description": "Premium household contents insurance",
                    "productLine": "HOUSEHOLD_CONTENTS",
                    "basePremium": 250.00
                }""")
        .when()
            .post("/api/products")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .extract().jsonPath().getString("id");

        assertNotNull(productId);

        // GET the created product
        given()
        .when()
            .get("/api/products/" + productId)
        .then()
            .statusCode(200)
            .body("name", equalTo("Hausrat Premium"))
            .body("productLine", equalTo("HOUSEHOLD_CONTENTS"))
            .body("status", equalTo("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/products/{id} with unknown ID returns 404")
    void getProduct_unknownId_returns404() {
        given()
        .when()
            .get("/api/products/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404);
    }

    @Test
    @DisplayName("PUT /api/products/{id} updates an existing product")
    void updateProduct_returns200() {
        // Create a product first
        String productId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Original Product",
                    "description": "Original description",
                    "productLine": "LIABILITY",
                    "basePremium": 100.00
                }""")
        .when()
            .post("/api/products")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("id");

        // Update the product
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Updated Product",
                    "description": "Updated description",
                    "productLine": "LIABILITY",
                    "basePremium": 150.00
                }""")
        .when()
            .put("/api/products/" + productId)
        .then()
            .statusCode(200)
            .body("name", equalTo("Updated Product"))
            .body("basePremium", comparesEqualTo(150.00f));
    }

    @Test
    @DisplayName("POST /api/products/{id}/deprecate deprecates a product")
    void deprecateProduct_returns200() {
        // Create a product first
        String productId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "To Deprecate",
                    "description": "Will be deprecated",
                    "productLine": "TRAVEL",
                    "basePremium": 80.00
                }""")
        .when()
            .post("/api/products")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("id");

        // Deprecate the product
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/products/" + productId + "/deprecate")
        .then()
            .statusCode(200)
            .body("status", equalTo("DEPRECATED"));
    }

    @Test
    @DisplayName("POST /api/products with missing required fields returns 400")
    void defineProduct_missingFields_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "",
                    "description": "No name",
                    "productLine": "LIABILITY",
                    "basePremium": 100.00
                }""")
        .when()
            .post("/api/products")
        .then()
            .statusCode(400);
    }
}
