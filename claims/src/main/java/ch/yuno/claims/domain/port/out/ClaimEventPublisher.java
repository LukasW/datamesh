package ch.yuno.claims.domain.port.out;

import ch.yuno.claims.domain.model.Claim;

import java.math.BigDecimal;

public interface ClaimEventPublisher {
    void claimOpened(Claim claim);
    void claimUnderReview(Claim claim);
    void claimSettled(Claim claim, BigDecimal settlementAmount);
    void claimRejected(Claim claim);
}
