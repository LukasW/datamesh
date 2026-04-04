package ch.yuno.policy.infrastructure.messaging;

import ch.yuno.policy.domain.model.CoverageId;
import ch.yuno.policy.domain.model.CoverageType;
import ch.yuno.policy.domain.model.Policy;
import ch.yuno.policy.domain.model.PolicyId;
import ch.yuno.policy.domain.port.out.OutboxRepository;
import ch.yuno.policy.domain.port.out.PolicyEventPublisher;
import ch.yuno.policy.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.UUID;

@ApplicationScoped
public class PolicyEventPublisherAdapter implements PolicyEventPublisher {

    @Inject
    OutboxRepository outboxRepository;

    @Override
    public void policyIssued(Policy policy) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policy.getPolicyId().value(), "PolicyIssued",
                PolicyEventPayloadBuilder.TOPIC_POLICY_ISSUED,
                PolicyEventPayloadBuilder.buildPolicyIssued(
                        policy.getPolicyId().value(), policy.getPolicyNumber(),
                        policy.getPartnerId(), policy.getProductId(),
                        policy.getCoverageStartDate(), policy.getPremium())));
    }

    @Override
    public void policyCancelled(Policy policy) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policy.getPolicyId().value(), "PolicyCancelled",
                PolicyEventPayloadBuilder.TOPIC_POLICY_CANCELLED,
                PolicyEventPayloadBuilder.buildPolicyCancelled(
                        policy.getPolicyId().value(), policy.getPolicyNumber())));
    }

    @Override
    public void policyChanged(Policy policy) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policy.getPolicyId().value(), "PolicyChanged",
                PolicyEventPayloadBuilder.TOPIC_POLICY_CHANGED,
                PolicyEventPayloadBuilder.buildPolicyChanged(
                        policy.getPolicyId().value(), policy.getPolicyNumber(),
                        policy.getPremium(), policy.getDeductible())));
    }

    @Override
    public void coverageAdded(PolicyId policyId, CoverageId coverageId, CoverageType coverageType, BigDecimal insuredAmount) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policyId.value(), "CoverageAdded",
                PolicyEventPayloadBuilder.TOPIC_COVERAGE_ADDED,
                PolicyEventPayloadBuilder.buildCoverageAdded(
                        policyId.value(), coverageId.value(), coverageType, insuredAmount)));
    }

    @Override
    public void coverageRemoved(PolicyId policyId, CoverageId coverageId) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "policy", policyId.value(), "CoverageRemoved",
                PolicyEventPayloadBuilder.TOPIC_COVERAGE_REMOVED,
                PolicyEventPayloadBuilder.buildCoverageRemoved(
                        policyId.value(), coverageId.value())));
    }
}
