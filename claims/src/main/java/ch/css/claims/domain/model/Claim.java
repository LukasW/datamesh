package ch.css.claims.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Aggregate root for the Claims domain.
 * Represents an insurance claim filed against a policy.
 */
public class Claim {

    private final String claimId;
    private final String policyId;
    private final String claimNumber;
    private final String description;
    private final LocalDate claimDate;
    private ClaimStatus status;
    private final Instant createdAt;

    private Claim(String claimId, String policyId, String claimNumber,
                  String description, LocalDate claimDate, ClaimStatus status,
                  Instant createdAt) {
        this.claimId = claimId;
        this.policyId = policyId;
        this.claimNumber = claimNumber;
        this.description = description;
        this.claimDate = claimDate;
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * Factory method to create a new claim (First Notice of Loss).
     *
     * @param policyId    the policy this claim is filed against
     * @param description a description of the damage
     * @param claimDate   the date the damage occurred
     * @return a new Claim in OPEN status
     */
    public static Claim openNew(String policyId, String description, LocalDate claimDate) {
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("Policy ID must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description must not be blank");
        }
        if (claimDate == null) {
            throw new IllegalArgumentException("Claim date must not be null");
        }

        String id = UUID.randomUUID().toString();
        String number = generateClaimNumber(claimDate);
        return new Claim(id, policyId, number, description, claimDate, ClaimStatus.OPEN, Instant.now());
    }

    /**
     * Reconstruct a Claim from persistence (no validation, trusted data).
     */
    public static Claim reconstitute(String claimId, String policyId, String claimNumber,
                                     String description, LocalDate claimDate,
                                     ClaimStatus status, Instant createdAt) {
        return new Claim(claimId, policyId, claimNumber, description, claimDate, status, createdAt);
    }

    public void startReview() {
        if (this.status != ClaimStatus.OPEN) {
            throw new IllegalStateException("Only OPEN claims can be moved to IN_REVIEW");
        }
        this.status = ClaimStatus.IN_REVIEW;
    }

    public void settle() {
        if (this.status != ClaimStatus.IN_REVIEW) {
            throw new IllegalStateException("Only IN_REVIEW claims can be settled");
        }
        this.status = ClaimStatus.SETTLED;
    }

    public void reject() {
        if (this.status != ClaimStatus.OPEN && this.status != ClaimStatus.IN_REVIEW) {
            throw new IllegalStateException("Only OPEN or IN_REVIEW claims can be rejected");
        }
        this.status = ClaimStatus.REJECTED;
    }

    // --- Getters (no framework annotations) ---

    public String getClaimId() {
        return claimId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getClaimDate() {
        return claimDate;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String generateClaimNumber(LocalDate claimDate) {
        String datePart = claimDate.toString().replace("-", "");
        String randomPart = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return "CLM-" + datePart + "-" + randomPart;
    }
}
