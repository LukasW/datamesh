package ch.css.product.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds JSON payloads for product domain events written to the outbox table.
 * Produces byte-for-byte identical output to the former ProductKafkaAdapter,
 * ensuring downstream consumers require no changes.
 * No framework dependencies – pure infrastructure helper for serialization.
 */
public final class ProductEventPayloadBuilder {

    public static final String TOPIC_PRODUCT_DEFINED    = "product.v1.defined";
    public static final String TOPIC_PRODUCT_UPDATED    = "product.v1.updated";
    public static final String TOPIC_PRODUCT_DEPRECATED = "product.v1.deprecated";
    public static final String TOPIC_PRODUCT_STATE      = "product.v1.state";

    private ProductEventPayloadBuilder() {}

    public static String buildProductDefined(String productId, String name,
                                             String productLine, BigDecimal basePremium) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"ProductDefined\",\"productId\":\"%s\"," +
                "\"name\":\"%s\",\"productLine\":\"%s\",\"basePremium\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), productId, escape(name), productLine, basePremium, Instant.now());
    }

    public static String buildProductUpdated(String productId, String name,
                                             String productLine, BigDecimal basePremium) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"ProductUpdated\",\"productId\":\"%s\"," +
                "\"name\":\"%s\",\"productLine\":\"%s\",\"basePremium\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), productId, escape(name), productLine, basePremium, Instant.now());
    }

    public static String buildProductDeprecated(String productId) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"ProductDeprecated\",\"productId\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), productId, Instant.now());
    }

    /**
     * Builds the full current state of a product (Event-Carried State Transfer).
     * Written to the compacted topic product.v1.state on every state change.
     * Downstream services use this to bootstrap and maintain a local materialized view.
     */
    public static String buildProductState(String productId, String name,
                                           String productLine, BigDecimal basePremium,
                                           String status) {
        return String.format(
                "{\"eventType\":\"ProductState\",\"productId\":\"%s\"," +
                "\"name\":\"%s\",\"productLine\":\"%s\",\"basePremium\":%s," +
                "\"status\":\"%s\",\"deleted\":false,\"timestamp\":\"%s\"}",
                productId, escape(name), productLine, basePremium, status, Instant.now());
    }

    /**
     * Semantic tombstone for the compacted state topic: signals that the product no longer exists.
     * Downstream services must remove the product from their local materialized view on receipt.
     */
    public static String buildProductStateDeleted(String productId) {
        return String.format(
                "{\"eventType\":\"ProductState\",\"productId\":\"%s\",\"deleted\":true,\"timestamp\":\"%s\"}",
                productId, Instant.now());
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
