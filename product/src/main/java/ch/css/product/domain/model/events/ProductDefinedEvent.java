package ch.css.product.domain.model.events;

import java.math.BigDecimal;

public record ProductDefinedEvent(
    String productId,
    String name,
    String productLine,
    BigDecimal basePremium
) {}
