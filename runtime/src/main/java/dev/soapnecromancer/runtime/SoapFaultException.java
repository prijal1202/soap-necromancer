package dev.soapnecromancer.runtime;

/**
 * The SOAP endpoint answered with a SOAP Fault. Whether the caller is to blame
 * is decided by
 * {@link SoapClient}, which resolves the fault code as a QName against the
 * envelope namespace.
 */
public class SoapFaultException extends RuntimeException {
    /**
     * Generators Requirement 2.12: backend text forwarded to clients is bounded.
     */
    public static final int MAX_FAULT_STRING = 1024;

    private final String faultCode;
    private final String faultString;
    private final boolean clientFault;

    public SoapFaultException(String faultCode, String faultString, boolean clientFault) {
        super(normalize(faultCode, 256) + ": " + normalize(faultString, MAX_FAULT_STRING));
        this.faultCode = normalize(faultCode, 256);
        this.faultString = normalize(faultString, MAX_FAULT_STRING);
        this.clientFault = clientFault;
    }

    private static String normalize(String s, int max) {
        if (s == null)
            return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public String faultCode() {
        return faultCode;
    }

    public String faultString() {
        return faultString;
    }

    /**
     * The caller's request was rejected (SOAP 1.1 {@code Client}): the generated
     * proxy answers 400.
     */
    public boolean isClientFault() {
        return clientFault;
    }
}
