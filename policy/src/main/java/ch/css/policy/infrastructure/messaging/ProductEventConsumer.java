package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.ProductView;
import ch.css.policy.domain.port.out.ProductViewRepository;
import ch.css.policy.infrastructure.messaging.acl.ProductEventTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Kafka consumer for Product domain events.
 * Materializes the local ProductView read model from product.v1.defined,
 * product.v1.updated and product.v1.deprecated events (ADR-001).
 * Messages arrive as JSON strings published by Debezium via StringConverter.
 *
 * Translation from external Product schema to local ProductView is delegated
 * to the Anti-Corruption Layer ({@link ProductEventTranslator}).
 *
 * Failed messages are routed to a dead-letter-queue topic for later inspection.
 */
@ApplicationScoped
public class ProductEventConsumer {

    private static final Logger log = Logger.getLogger(ProductEventConsumer.class);

    @Inject
    ProductViewRepository productViewRepository;

    @Inject
    ObjectMapper objectMapper;

    private ProductEventTranslator translator;

    ProductEventTranslator getTranslator() {
        if (translator == null) {
            translator = new ProductEventTranslator(objectMapper);
        }
        return translator;
    }

    @Incoming("product-defined-in")
    @Transactional
    public void onProductDefined(String payload) {
        log.infof("Received Kafka event [product.v1.defined]");
        try {
            ProductView view = getTranslator().translate(payload);
            productViewRepository.upsert(view);
            log.infof("ProductView upserted: %s -> %s (%s)", view.getProductId(), view.getName(), view.getProductLine());
        } catch (Exception e) {
            log.errorf(e, "Failed to process product.v1.defined event, routing to DLQ. Payload: %s", payload);
            throw new RuntimeException("Failed to process product.v1.defined event: " + e.getMessage(), e);
        }
    }

    @Incoming("product-updated-in")
    @Transactional
    public void onProductUpdated(String payload) {
        log.infof("Received Kafka event [product.v1.updated]");
        try {
            ProductView view = getTranslator().translate(payload);
            productViewRepository.upsert(view);
            log.infof("ProductView upserted: %s -> %s (%s)", view.getProductId(), view.getName(), view.getProductLine());
        } catch (Exception e) {
            log.errorf(e, "Failed to process product.v1.updated event, routing to DLQ. Payload: %s", payload);
            throw new RuntimeException("Failed to process product.v1.updated event: " + e.getMessage(), e);
        }
    }

    @Incoming("product-deprecated-in")
    @Transactional
    public void onProductDeprecated(String payload) {
        log.infof("Received Kafka event [product.v1.deprecated]");
        try {
            String productId = getTranslator().extractProductId(payload);
            productViewRepository.deactivate(productId);
            log.infof("ProductView deactivated: %s", productId);
        } catch (Exception e) {
            log.errorf(e, "Failed to process product.v1.deprecated event, routing to DLQ. Payload: %s", payload);
            throw new RuntimeException("Failed to process product.v1.deprecated event: " + e.getMessage(), e);
        }
    }
}
