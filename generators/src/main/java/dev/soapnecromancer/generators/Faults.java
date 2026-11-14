package dev.soapnecromancer.generators;

import dev.soapnecromancer.runtime.model.SoapVersion;

/** The fault-code names generated text uses for each SOAP version. */
final class Faults {
    private Faults() {
    }

    /** The code that blames the caller: SOAP 1.1 {@code Client}, SOAP 1.2 {@code Sender}. */
    static String client(SoapVersion v) {
        return switch (v) {
            case SOAP_11 -> "Client";
            case SOAP_12 -> "Sender";
        };
    }

    /** The code that blames the backend: SOAP 1.1 {@code Server}, SOAP 1.2 {@code Receiver}. */
    static String server(SoapVersion v) {
        return switch (v) {
            case SOAP_11 -> "Server";
            case SOAP_12 -> "Receiver";
        };
    }
}
