package ch.css.policy.domain.model.events;

import java.math.BigDecimal;

public record CoverageAddedEvent(
    String policyId,
    String coverageId,
    String coverageType,
    BigDecimal insuredAmount
) {}
