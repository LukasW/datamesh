package ch.yuno.claims.application;

/**
 * Thrown when the coverage check for a policy fails or the policy does not provide coverage.
 */
public class CoverageCheckFailedException extends RuntimeException {

    public CoverageCheckFailedException(String policyId) {
        super("Coverage check failed for policy: " + policyId);
    }
}
