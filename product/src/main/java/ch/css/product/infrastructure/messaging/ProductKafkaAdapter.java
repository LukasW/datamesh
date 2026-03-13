package ch.css.product.infrastructure.messaging;

import ch.css.product.domain.model.Product;
import ch.css.product.domain.port.out.ProductEventPublisher;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class ProductKafkaAdapter implements ProductEventPublisher {

    private static final Logger log = Logger.getLogger(ProductKafkaAdapter.class);

    @Inject
    @Channel("product-defined")
    Emitter<String> productDefinedEmitter;

    @Inject
    @Channel("product-updated")
    Emitter<String> productUpdatedEmitter;

    @Inject
    @Channel("product-deprecated")
    Emitter<String> productDeprecatedEmitter;

    @Override
    public void publishProductDefined(Product product) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"ProductDefined\",\"productId\":\"%s\"," +
                "\"name\":\"%s\",\"productLine\":\"%s\",\"basePremium\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), product.getProductId(),
                escape(product.getName()), product.getProductLine().name(),
                product.getBasePremium(), Instant.now());
        send(productDefinedEmitter, product.getProductId(), json, "ProductDefined");
    }

    @Override
    public void publishProductUpdated(Product product) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"ProductUpdated\",\"productId\":\"%s\"," +
                "\"name\":\"%s\",\"productLine\":\"%s\",\"basePremium\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), product.getProductId(),
                escape(product.getName()), product.getProductLine().name(),
                product.getBasePremium(), Instant.now());
        send(productUpdatedEmitter, product.getProductId(), json, "ProductUpdated");
    }

    @Override
    public void publishProductDeprecated(String productId) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"ProductDeprecated\",\"productId\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), productId, Instant.now());
        send(productDeprecatedEmitter, productId, json, "ProductDeprecated");
    }

    private void send(Emitter<String> emitter, String key, String json, String eventType) {
        try {
            emitter.send(KafkaRecord.of(key, json));
        } catch (Exception e) {
            log.warnf("Failed to publish %s event for %s: %s", eventType, key, e.getMessage());
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
