package dev.soapnecromancer.runtime;

/** The SOAP call failed without a SOAP Fault. Messages never include the backend's raw body. */
public class SoapTransportException extends RuntimeException {
    public enum Kind {
        /** No answer within the connect or read timeout. */
        TIMEOUT,
        /** The endpoint could not be reached. */
        CONNECT,
        /** The endpoint answered, but not with a usable SOAP response. */
        BAD_RESPONSE
    }

    private final Kind kind;

    public SoapTransportException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
