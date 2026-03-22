package ch.yuno.policy.domain.model.events;

import java.math.BigDecimal;

public record PolicyChangedEvent(
    String policyId,
    String policyNumber,
    BigDecimal premium,
    BigDecimal deductible
) {}
