package ch.yuno.policy.domain.service;

public class CoverageNotFoundException extends RuntimeException {
    public CoverageNotFoundException(String coverageId) {
        super("Coverage not found: " + coverageId);
    }
}
