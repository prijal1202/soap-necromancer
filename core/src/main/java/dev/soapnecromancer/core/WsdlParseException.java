package dev.soapnecromancer.core;

import java.util.List;

/** The input cannot produce any operation. Carries the warnings collected before failing. */
public class WsdlParseException extends RuntimeException {
    private final List<Warning> warnings;

    public WsdlParseException(String message, List<Warning> warnings) {
        super(message);
        this.warnings = List.copyOf(warnings);
    }

    public WsdlParseException(String message, Throwable cause) {
        super(message, cause);
        this.warnings = List.of();
    }

    public List<Warning> warnings() {
        return warnings;
    }
}
