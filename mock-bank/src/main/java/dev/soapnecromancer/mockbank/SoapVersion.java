package dev.soapnecromancer.mockbank;

import org.springframework.http.MediaType;

/**
 * The SOAP versions the mock bank speaks. Kept separate from the generator's
 * own enum on purpose: mock-bank depends on none of the generator modules.
 */
enum SoapVersion {
    SOAP_11("http://schemas.xmlsoap.org/soap/envelope/", "text/xml;charset=UTF-8"),
    SOAP_12("http://www.w3.org/2003/05/soap-envelope", "application/soap+xml;charset=UTF-8");

    private final String envelopeNamespace;
    private final MediaType mediaType;

    SoapVersion(String envelopeNamespace, String mediaType) {
        this.envelopeNamespace = envelopeNamespace;
        this.mediaType = MediaType.parseMediaType(mediaType);
    }

    String envelopeNamespace() {
        return envelopeNamespace;
    }

    MediaType mediaType() {
        return mediaType;
    }

    String label() {
        return this == SOAP_11 ? "1.1" : "1.2";
    }
}
