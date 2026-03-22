package ch.yuno.partner.domain.model;

/**
 * Value Object: Unique insured number assigned to a person when their first policy is activated.
 * Format: VN-XXXXXXXX (VN prefix + 8-digit zero-padded sequence number).
 * Immutable, validated at construction.
 */
public record InsuredNumber(String value) {

    public static final String PREFIX = "VN-";

    public InsuredNumber {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Insured number must not be blank");
        if (!value.matches("VN-\\d{8}"))
            throw new IllegalArgumentException("Insured number must match VN-XXXXXXXX: " + value);
    }

    /** Creates an InsuredNumber from a raw sequence number (e.g. 42 → VN-00000042). */
    public static InsuredNumber fromSequence(long sequenceValue) {
        return new InsuredNumber(PREFIX + String.format("%08d", sequenceValue));
    }

    /** Returns the formatted display value (e.g. "VN-00000042"). */
    public String formatted() { return value; }

    @Override
    public String toString() { return value; }
}

