package ch.yuno.policy.domain.model.events;

public record CoverageRemovedEvent(
    String policyId,
    String coverageId
) {}
