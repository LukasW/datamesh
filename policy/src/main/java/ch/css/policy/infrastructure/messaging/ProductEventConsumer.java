package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.ProductView;
import ch.css.policy.domain.port.out.ProductViewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Kafka consumer for Product domain events.
 * Materializes the local ProductView read model from product.v1.defined,
 * product.v1.updated and product.v1.deprecated events (ADR-001).
 * Messages arrive as JSON strings published by Debezium via StringConverter.
 */
@ApplicationScoped
public class ProductEventConsumer {

    private static final Logger log = Logger.getLogger(ProductEventConsumer.class);

    @Inject
    ProductViewRepository productViewRepository;

    @Inject
    ObjectMapper objectMapper;

    @Incoming("product-defined-in")
    @Transactional
    public void onProductDefined(String payload) {
        log.infof("Received Kafka event [product.v1.defined]");
        upsertProductFromJson(payload);
    }

    @Incoming("product-updated-in")
    @Transactional
    public void onProductUpdated(String payload) {
        log.infof("Received Kafka event [product.v1.updated]");
        upsertProductFromJson(payload);
    }

    @Incoming("product-deprecated-in")
    @Transactional
    public void onProductDeprecated(String payload) {
        log.infof("Received Kafka event [product.v1.deprecated]");
        try {
            JsonNode json = objectMapper.readTree(payload);
            String productId = json.path("productId").asText(null);
            if (productId == null || productId.isEmpty()) {
                log.warnf("ProductDeprecated missing productId");
                return;
            }
            productViewRepository.deactivate(productId);
            log.infof("ProductView deactivated: %s", productId);
        } catch (Exception e) {
            log.errorf("Failed to process ProductDeprecated event: %s", e.getMessage());
        }
    }

    private void upsertProductFromJson(String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            String productId   = json.path("productId").asText(null);
            String name        = json.path("name").asText(null);
            String productLine = json.path("productLine").asText("UNKNOWN");
            BigDecimal premium = json.has("basePremium") && !json.path("basePremium").isNull()
                    ? new BigDecimal(json.path("basePremium").asText())
                    : BigDecimal.ZERO;
            if (productId == null || productId.isEmpty() || name == null) {
                log.warnf("Product event missing required fields: productId=%s name=%s", productId, name);
                return;
            }
            productViewRepository.upsert(new ProductView(productId, name, productLine, premium, true));
            log.infof("ProductView upserted: %s -> %s (%s)", productId, name, productLine);
        } catch (Exception e) {
            log.errorf("Failed to process product event: %s", e.getMessage());
        }
    }
}
