package ch.css.product.domain.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds JSON payloads for product domain events written to the outbox table.
 * Produces byte-for-byte identical output to the former ProductKafkaAdapter,
 * ensuring downstream consumers require no changes.
 * No framework dependencies – pure domain helper.
 */
public final class ProductEventPayloadBuilder {

    public static final String TOPIC_PRODUCT_DEFINED    = "product.v1.defined";
    public static final String TOPIC_PRODUCT_UPDATED    = "product.v1.updated";
    public static final String TOPIC_PRODUCT_DEPRECATED = "product.v1.deprecated";

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

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
