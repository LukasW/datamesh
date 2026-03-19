package ch.css.policy.domain.model.events;

public record CoverageRemovedEvent(
    String policyId,
    String coverageId
) {}
