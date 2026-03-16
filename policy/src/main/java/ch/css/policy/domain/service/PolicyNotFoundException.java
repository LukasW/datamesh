package ch.css.policy.domain.service;

public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(String policyId) {
        super("Policy not found: " + policyId);
    }
}
