package dev.soapnecromancer.generators;

/**
 * The output for this WSDL would be too large for the hosted generator (generators Requirement 5.6).
 * The web layer maps it to 422 with {@link #code()} as the warning code.
 */
public class GenerationLimitException extends RuntimeException {
    private final String code;

    public GenerationLimitException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
