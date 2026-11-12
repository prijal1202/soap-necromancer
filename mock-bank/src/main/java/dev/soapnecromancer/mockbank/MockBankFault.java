package dev.soapnecromancer.mockbank;

/** Raised by the mock bank and rendered as a SOAP 1.1 Fault (HTTP 500). */
public class MockBankFault extends RuntimeException {
    private final String faultCode;

    public MockBankFault(String faultCode, String message) {
        super(message);
        this.faultCode = faultCode;
    }

    public String faultCode() {
        return faultCode;
    }
}
