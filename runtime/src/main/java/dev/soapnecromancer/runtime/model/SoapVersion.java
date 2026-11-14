package dev.soapnecromancer.runtime.model;

/**
 * The SOAP version of the service's binding. Carries only data, so the model stays plain records
 * and enums; the version-specific behaviour lives in {@code SoapClient}.
 */
public enum SoapVersion {
    SOAP_11("http://schemas.xmlsoap.org/soap/envelope/", "text/xml", "1.1"),
    SOAP_12("http://www.w3.org/2003/05/soap-envelope", "application/soap+xml", "1.2");

    private final String envelopeNamespace;
    private final String mediaType;
    private final String label;

    SoapVersion(String envelopeNamespace, String mediaType, String label) {
        this.envelopeNamespace = envelopeNamespace;
        this.mediaType = mediaType;
        this.label = label;
    }

    public String envelopeNamespace() {
        return envelopeNamespace;
    }

    /** The request and response media type, without parameters. */
    public String mediaType() {
        return mediaType;
    }

    /** "1.1" or "1.2", for messages and generated documentation. */
    public String label() {
        return label;
    }

    /** The version whose envelope uses {@code namespace}, or null. */
    public static SoapVersion ofEnvelopeNamespace(String namespace) {
        for (SoapVersion v : values()) {
            if (v.envelopeNamespace.equals(namespace))
                return v;
        }
        return null;
    }
}
