package ch.yuno.claims.infrastructure.messaging;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.port.out.ClaimEventPublisher;
import ch.yuno.claims.domain.port.out.OutboxRepository;
import ch.yuno.claims.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Infrastructure adapter that publishes claim domain events
 * via the Transactional Outbox pattern.
 */
@ApplicationScoped
public class ClaimEventPublisherAdapter implements ClaimEventPublisher {

    private final OutboxRepository outboxRepository;

    public ClaimEventPublisherAdapter(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    public void claimOpened(Claim claim) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "claims", claim.getClaimId().value(), "ClaimOpened",
                ClaimsEventPayloadBuilder.TOPIC_CLAIM_OPENED,
                ClaimsEventPayloadBuilder.buildClaimOpened(claim)));
    }

    @Override
    public void claimUnderReview(Claim claim) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "claims", claim.getClaimId().value(), "ClaimUnderReview",
                ClaimsEventPayloadBuilder.TOPIC_CLAIM_UNDER_REVIEW,
                ClaimsEventPayloadBuilder.buildClaimUnderReview(claim)));
    }

    @Override
    public void claimSettled(Claim claim, BigDecimal settlementAmount) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "claims", claim.getClaimId().value(), "ClaimSettled",
                ClaimsEventPayloadBuilder.TOPIC_CLAIM_SETTLED,
                ClaimsEventPayloadBuilder.buildClaimSettled(claim, settlementAmount)));
    }

    @Override
    public void claimRejected(Claim claim) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "claims", claim.getClaimId().value(), "ClaimRejected",
                ClaimsEventPayloadBuilder.TOPIC_CLAIM_REJECTED,
                ClaimsEventPayloadBuilder.buildClaimRejected(claim)));
    }
}
