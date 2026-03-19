package ch.css.policy.domain.model.events;

public record PolicyCancelledEvent(
    String policyId,
    String policyNumber
) {}
