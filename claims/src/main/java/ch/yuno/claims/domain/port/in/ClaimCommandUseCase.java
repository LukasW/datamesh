package ch.yuno.claims.domain.port.in;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.model.ClaimId;

import java.time.LocalDate;

/**
 * Inbound port for claim command use cases.
 */
public interface ClaimCommandUseCase {

    Claim openClaim(String policyId, String description, LocalDate claimDate);

    Claim startReview(ClaimId claimId);

    Claim settle(ClaimId claimId);

    Claim reject(ClaimId claimId);

    Claim updateClaim(ClaimId claimId, String description, LocalDate claimDate);
}
