package ch.yuno.claims.domain.model;

/**
 * Represents the lifecycle status of an insurance claim.
 */
public enum ClaimStatus {

    /** Claim created, awaiting review. */
    OPEN,

    /** Under assessment by a claims agent. */
    IN_REVIEW,

    /** Claim approved and payout initiated. */
    SETTLED,

    /** Claim denied (no coverage or invalid). */
    REJECTED
}
