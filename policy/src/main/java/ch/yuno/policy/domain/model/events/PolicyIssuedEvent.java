package ch.yuno.policy.domain.model.events;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PolicyIssuedEvent(
    String policyId,
    String policyNumber,
    String partnerId,
    String productId,
    LocalDate coverageStartDate,
    BigDecimal premium
) {}
