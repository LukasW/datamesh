package ch.yuno.policy.application;

public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(String policyId) {
        super("Policy not found: " + policyId);
    }
}
