package ch.yuno.policy.domain.model;

import ch.yuno.policy.domain.service.CoverageNotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate Root: Policy (insurance contract).
 * All state changes go through this aggregate. Invariants are enforced here.
 */
public class Policy {

    private final PolicyId policyId;
    private final String policyNumber;
    private final String partnerId;
    private String productId;
    private PolicyStatus status;
    private LocalDate coverageStartDate;
    private LocalDate coverageEndDate; // null = open-ended
    private BigDecimal premium;
    private BigDecimal deductible;
    private List<Coverage> coverages;

    /** Constructor for creating a new Policy (status = DRAFT). */
    public Policy(String policyNumber, String partnerId, String productId,
                  LocalDate coverageStartDate, LocalDate coverageEndDate,
                  BigDecimal premium, BigDecimal deductible) {
        validate(policyNumber, partnerId, productId, coverageStartDate, premium, deductible);
        if (coverageEndDate != null && coverageStartDate.isAfter(coverageEndDate)) {
            throw new IllegalArgumentException("coverageStartDate must not be after coverageEndDate");
        }
        this.policyId = PolicyId.generate();
        this.policyNumber = policyNumber;
        this.partnerId = partnerId;
        this.productId = productId;
        this.status = PolicyStatus.DRAFT;
        this.coverageStartDate = coverageStartDate;
        this.coverageEndDate = coverageEndDate;
        this.premium = premium;
        this.deductible = deductible;
        this.coverages = new ArrayList<>();
    }

    /** Constructor for reconstructing from persistence. */
    public Policy(PolicyId policyId, String policyNumber, String partnerId, String productId,
                  PolicyStatus status, LocalDate coverageStartDate, LocalDate coverageEndDate,
                  BigDecimal premium, BigDecimal deductible) {
        this.policyId = policyId;
        this.policyNumber = policyNumber;
        this.partnerId = partnerId;
        this.productId = productId;
        this.status = status;
        this.coverageStartDate = coverageStartDate;
        this.coverageEndDate = coverageEndDate;
        this.premium = premium;
        this.deductible = deductible;
        this.coverages = new ArrayList<>();
    }

    // ── Business Methods ──────────────────────────────────────────────────────

    /** Activates the policy (DRAFT → ACTIVE). */
    public void activate() {
        if (status != PolicyStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT policies can be activated (current status: " + status + ")");
        }
        this.status = PolicyStatus.ACTIVE;
    }

    /** Cancels the policy (ACTIVE → CANCELLED). */
    public void cancel() {
        if (status != PolicyStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE policies can be cancelled (current status: " + status + ")");
        }
        this.status = PolicyStatus.CANCELLED;
    }

    /** Updates the policy details. Only allowed in DRAFT or ACTIVE status. */
    public void updateDetails(String productId, LocalDate coverageStartDate,
                               LocalDate coverageEndDate, BigDecimal premium, BigDecimal deductible) {
        if (status == PolicyStatus.CANCELLED || status == PolicyStatus.EXPIRED) {
            throw new IllegalStateException("CANCELLED or EXPIRED policies cannot be modified");
        }
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId is required");
        if (coverageStartDate == null) throw new IllegalArgumentException("coverageStartDate is required");
        if (premium == null || premium.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("premium must be greater than 0");
        if (deductible == null || deductible.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("deductible must not be negative");
        if (coverageEndDate != null && coverageStartDate.isAfter(coverageEndDate)) {
            throw new IllegalArgumentException("coverageStartDate must not be after coverageEndDate");
        }
        this.productId = productId;
        this.coverageStartDate = coverageStartDate;
        this.coverageEndDate = coverageEndDate;
        this.premium = premium;
        this.deductible = deductible;
    }

    /**
     * Adds a coverage to the policy.
     * Each CoverageType may only appear once per policy.
     * @return the new coverageId
     */
    public CoverageId addCoverage(CoverageType coverageType, BigDecimal insuredAmount) {
        if (status == PolicyStatus.CANCELLED || status == PolicyStatus.EXPIRED) {
            throw new IllegalStateException("Coverages cannot be added to this policy");
        }
        boolean exists = coverages.stream().anyMatch(c -> c.getCoverageType() == coverageType);
        if (exists) {
            throw new IllegalArgumentException("Coverage type " + coverageType + " already exists");
        }
        CoverageId coverageId = CoverageId.generate();
        coverages.add(new Coverage(coverageId, policyId, coverageType, insuredAmount));
        return coverageId;
    }

    /** Removes a coverage by ID. */
    public void removeCoverage(CoverageId coverageId) {
        findCoverage(coverageId); // throws CoverageNotFoundException if not found
        coverages.removeIf(c -> c.getCoverageId().equals(coverageId));
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Coverage findCoverage(CoverageId coverageId) {
        return coverages.stream()
                .filter(c -> c.getCoverageId().equals(coverageId))
                .findFirst()
                .orElseThrow(() -> new CoverageNotFoundException(coverageId.value()));
    }

    private static void validate(String policyNumber, String partnerId, String productId,
                                  LocalDate coverageStartDate, BigDecimal premium, BigDecimal deductible) {
        if (policyNumber == null || policyNumber.isBlank()) throw new IllegalArgumentException("policyNumber is required");
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId is required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId is required");
        if (coverageStartDate == null) throw new IllegalArgumentException("coverageStartDate is required");
        if (premium == null || premium.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("premium must be greater than 0");
        if (deductible == null || deductible.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("deductible must not be negative");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public PolicyId getPolicyId() { return policyId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getPartnerId() { return partnerId; }
    public String getProductId() { return productId; }
    public PolicyStatus getStatus() { return status; }
    public LocalDate getCoverageStartDate() { return coverageStartDate; }
    public LocalDate getCoverageEndDate() { return coverageEndDate; }
    public BigDecimal getPremium() { return premium; }
    public BigDecimal getDeductible() { return deductible; }
    public List<Coverage> getCoverages() { return coverages; }

    /** Used by JPA adapter to restore persisted coverages. */
    public void setCoverages(List<Coverage> coverages) {
        this.coverages = coverages != null ? new ArrayList<>(coverages) : new ArrayList<>();
    }

    /** Used by JPA adapter to restore status after persistence. */
    public void setStatus(PolicyStatus status) {
        this.status = status;
    }
}
