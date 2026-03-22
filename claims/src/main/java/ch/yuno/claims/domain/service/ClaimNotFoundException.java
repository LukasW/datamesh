package ch.yuno.claims.domain.service;

/**
 * Thrown when a claim cannot be found by the given identifier.
 */
public class ClaimNotFoundException extends RuntimeException {

    public ClaimNotFoundException(String claimId) {
        super("Claim not found: " + claimId);
    }
}
