package dev.soapnecromancer.runtime;

/** The SOAP endpoint answered with a SOAP 1.1 Fault. */
public class SoapFaultException extends RuntimeException {
    private final String faultCode;
    private final String faultString;

    public SoapFaultException(String faultCode, String faultString) {
        super(faultCode + ": " + faultString);
        this.faultCode = faultCode == null ? "" : faultCode;
        this.faultString = faultString == null ? "" : faultString;
    }

    public String faultCode() {
        return faultCode;
    }

    public String faultString() {
        return faultString;
    }

    /** {@code Client} or {@code Client.*} faultcode (any prefix): the caller's request was rejected. */
    public boolean isClientFault() {
        String local = faultCode.substring(faultCode.indexOf(':') + 1);
        return local.equals("Client") || local.startsWith("Client.");
    }
}
