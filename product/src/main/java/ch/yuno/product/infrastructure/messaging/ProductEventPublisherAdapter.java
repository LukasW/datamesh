package ch.yuno.product.infrastructure.messaging;

import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.port.out.OutboxRepository;
import ch.yuno.product.domain.port.out.ProductEventPublisher;
import ch.yuno.product.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class ProductEventPublisherAdapter implements ProductEventPublisher {

    @Inject
    OutboxRepository outboxRepository;

    @Override
    public void productDefined(Product product) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId().value(), "ProductDefined",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_DEFINED,
                ProductEventPayloadBuilder.buildProductDefined(
                        product.getProductId().value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId().value(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        product.getProductId().value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
    }

    @Override
    public void productUpdated(Product product) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId().value(), "ProductUpdated",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_UPDATED,
                ProductEventPayloadBuilder.buildProductUpdated(
                        product.getProductId().value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId().value(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        product.getProductId().value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
    }

    @Override
    public void productDeprecated(Product product) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId().value(), "ProductDeprecated",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_DEPRECATED,
                ProductEventPayloadBuilder.buildProductDeprecated(product.getProductId().value())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId().value(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        product.getProductId().value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
    }

    @Override
    public void productDeleted(ProductId productId) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId.value(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductStateDeleted(productId.value())));
    }
}
