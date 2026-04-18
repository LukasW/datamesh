package ch.yuno.claims.domain.port.out;

import ch.yuno.claims.domain.model.Claim;

public interface ClaimEventPublisher {
    void claimOpened(Claim claim);
    void claimUnderReview(Claim claim);
    void claimSettled(Claim claim);
    void claimRejected(Claim claim);
}
