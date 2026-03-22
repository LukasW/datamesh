package ch.yuno.policy.domain.service;

/**
 * Thrown when the premium calculation service (Product gRPC) is temporarily unavailable.
 * The policy operation must be retried later by the user.
 * <p>
 * Pure Java - no framework dependencies.
 */
public class PremiumCalculationUnavailableException extends RuntimeException {

    public PremiumCalculationUnavailableException(String message) {
        super(message);
    }

    public PremiumCalculationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

