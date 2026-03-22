package ch.yuno.policy.domain.model.events;

public record PolicyCancelledEvent(
    String policyId,
    String policyNumber
) {}
