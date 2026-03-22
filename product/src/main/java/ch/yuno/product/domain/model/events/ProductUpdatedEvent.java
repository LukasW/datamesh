package ch.yuno.product.domain.model.events;

import java.math.BigDecimal;

public record ProductUpdatedEvent(
    String productId,
    String name,
    String productLine,
    BigDecimal basePremium
) {}
