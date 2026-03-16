package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.ProductView;
import ch.css.policy.domain.port.out.ProductViewRepository;
import io.vertx.core.json.JsonObject;
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
 */
@ApplicationScoped
public class ProductEventConsumer {

    private static final Logger log = Logger.getLogger(ProductEventConsumer.class);

    @Inject
    ProductViewRepository productViewRepository;

    @Incoming("product-defined-in")
    @Transactional
    public void onProductDefined(String json) {
        log.infof("Received Kafka event [product.v1.defined]");
        upsertProductFromEvent(json);
    }

    @Incoming("product-updated-in")
    @Transactional
    public void onProductUpdated(String json) {
        log.infof("Received Kafka event [product.v1.updated]");
        upsertProductFromEvent(json);
    }

    @Incoming("product-deprecated-in")
    @Transactional
    public void onProductDeprecated(String json) {
        log.infof("Received Kafka event [product.v1.deprecated]");
        try {
            JsonObject event  = new JsonObject(json);
            String productId  = event.getString("productId");
            if (productId == null) {
                log.warnf("ProductDeprecated missing productId: %s", json);
                return;
            }
            productViewRepository.deactivate(productId);
            log.infof("ProductView deactivated: %s", productId);
        } catch (Exception e) {
            log.errorf("Failed to process ProductDeprecated event: %s | %s", e.getMessage(), json);
        }
    }

    private void upsertProductFromEvent(String json) {
        try {
            JsonObject event    = new JsonObject(json);
            String productId    = event.getString("productId");
            String name         = event.getString("name");
            String productLine  = event.getString("productLine", "UNKNOWN");
            BigDecimal premium  = event.containsKey("basePremium")
                    ? new BigDecimal(event.getValue("basePremium").toString())
                    : BigDecimal.ZERO;
            if (productId == null || name == null) {
                log.warnf("Product event missing required fields: %s", json);
                return;
            }
            productViewRepository.upsert(new ProductView(productId, name, productLine, premium, true));
            log.infof("ProductView upserted: %s -> %s (%s)", productId, name, productLine);
        } catch (Exception e) {
            log.errorf("Failed to process product event: %s | %s", e.getMessage(), json);
        }
    }
}
