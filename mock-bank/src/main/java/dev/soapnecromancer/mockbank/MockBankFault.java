package dev.soapnecromancer.mockbank;

/** Raised by the mock bank and rendered as a SOAP Fault. */
public class MockBankFault extends RuntimeException {

    /** Who is to blame, independent of the SOAP version that renders it. */
    public enum Code {
        /** The request was wrong: SOAP 1.1 {@code Client}. */
        SENDER,
        /** The bank failed: SOAP 1.1 {@code Server}. */
        RECEIVER,
        /** The envelope is not a SOAP envelope this bank speaks. */
        VERSION_MISMATCH;

        /** The SOAP 1.1 faultcode, using the envelope's {@code soap} prefix. */
        String soap11() {
            return switch (this) {
                case SENDER -> "soap:Client";
                case RECEIVER -> "soap:Server";
                case VERSION_MISMATCH -> "soap:VersionMismatch";
            };
        }

        /** The SOAP 1.2 Code/Value, using the envelope's {@code soap} prefix. */
        String soap12() {
            return switch (this) {
                case SENDER -> "soap:Sender";
                case RECEIVER -> "soap:Receiver";
                case VERSION_MISMATCH -> "soap:VersionMismatch";
            };
        }
    }

    private final Code code;

    private MockBankFault(Code code, String message) {
        super(message);
        this.code = code;
    }

    public static MockBankFault sender(String message) {
        return new MockBankFault(Code.SENDER, message);
    }

    public static MockBankFault receiver(String message) {
        return new MockBankFault(Code.RECEIVER, message);
    }

    static MockBankFault versionMismatch(String message) {
        return new MockBankFault(Code.VERSION_MISMATCH, message);
    }

    public Code code() {
        return code;
    }
}
