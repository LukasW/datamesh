package ch.css.claims.domain.service;

import ch.css.claims.domain.model.Claim;
import ch.css.claims.domain.port.out.ClaimRepository;
import ch.css.claims.domain.port.out.CoverageCheckPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Application service orchestrating claim use cases.
 * Acts as the entry point for the domain logic.
 */
@ApplicationScoped
@Transactional
public class ClaimApplicationService {

    private final ClaimRepository claimRepository;
    private final CoverageCheckPort coverageCheckPort;

    public ClaimApplicationService(ClaimRepository claimRepository,
                                   CoverageCheckPort coverageCheckPort) {
        this.claimRepository = claimRepository;
        this.coverageCheckPort = coverageCheckPort;
    }

    /**
     * Open a new claim (First Notice of Loss).
     * Performs a coverage check against the Policy service before creating the claim.
     *
     * @param policyId    the policy ID to file the claim against
     * @param description a description of the damage
     * @param claimDate   the date the damage occurred
     * @return the newly created claim
     * @throws CoverageCheckFailedException if the policy does not provide coverage
     */
    public Claim openClaim(String policyId, String description, LocalDate claimDate) {
        boolean covered = coverageCheckPort.checkCoverage(policyId);
        if (!covered) {
            throw new CoverageCheckFailedException(policyId);
        }

        Claim claim = Claim.openNew(policyId, description, claimDate);
        return claimRepository.save(claim);
    }

    /**
     * Find a claim by its unique identifier.
     *
     * @param claimId the claim ID
     * @return the claim
     * @throws ClaimNotFoundException if no claim exists with the given ID
     */
    public Claim findById(String claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    /**
     * Find all claims for a given policy.
     *
     * @param policyId the policy identifier
     * @return list of claims
     */
    public List<Claim> findByPolicyId(String policyId) {
        return claimRepository.findByPolicyId(policyId);
    }
}
