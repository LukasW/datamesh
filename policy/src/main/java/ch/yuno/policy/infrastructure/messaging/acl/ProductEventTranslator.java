package ch.yuno.policy.infrastructure.messaging.acl;

import ch.yuno.policy.domain.model.ProductView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/**
 * Anti-Corruption Layer: translates external Product domain events (ProductDefined/ProductUpdated)
 * into the local ProductView read model. Isolates the Policy domain from changes in the
 * Product domain's event schema.
 */
public class ProductEventTranslator {

    private final ObjectMapper objectMapper;

    public ProductEventTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Translates a product event JSON payload into a ProductView.
     *
     * @param payload JSON string from product.v1.defined or product.v1.updated
     * @return ProductView with product details
     * @throws IllegalArgumentException if required fields are missing
     */
    public ProductView translate(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String productId = json.path("productId").asText(null);
        String name = json.path("name").asText(null);
        String productLine = json.path("productLine").asText("UNKNOWN");
        BigDecimal premium = json.has("basePremium") && !json.path("basePremium").isNull()
                ? new BigDecimal(json.path("basePremium").asText())
                : BigDecimal.ZERO;

        if (productId == null || productId.isEmpty() || name == null) {
            throw new IllegalArgumentException(
                    "Product event missing required fields: productId=" + productId + " name=" + name);
        }

        return new ProductView(productId, name, productLine, premium, true);
    }

    /**
     * Extracts the product ID from a deprecated event payload.
     *
     * @param payload JSON string from product.v1.deprecated
     * @return the product ID
     * @throws IllegalArgumentException if productId is missing
     */
    public String extractProductId(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String productId = json.path("productId").asText(null);
        if (productId == null || productId.isEmpty()) {
            throw new IllegalArgumentException("ProductDeprecated event missing productId");
        }
        return productId;
    }
}
