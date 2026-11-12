package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.OperationModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * SOAP 1.1 document/literal client used by every generated proxy. JDK only (HttpClient + DOM).
 * Generators spec, Requirements 2.4, 2.6 and 2.9.
 *
 * <p>Request JSON errors surface as {@link ConversionException} before anything is sent. A SOAP
 * Fault becomes {@link SoapFaultException}; every other failure becomes {@link SoapTransportException}.
 */
public final class SoapClient {
    public static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";

    private final JsonXmlConverter converter;
    private final URI endpoint;
    private final Duration readTimeout;
    private final HttpClient http;

    public SoapClient(JsonXmlConverter converter, URI endpoint, Duration connectTimeout, Duration readTimeout) {
        this.converter = Objects.requireNonNull(converter);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.readTimeout = Objects.requireNonNull(readTimeout);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // legacy servers often mishandle the h2c upgrade
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public URI endpoint() {
        return endpoint;
    }

    /** Converts {@code json}, calls the operation, and returns the response payload as canonical JSON. */
    public Object call(OperationModel op, Object json) {
        String payload = converter.toXml(op.input(), json);
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap:Envelope xmlns:soap=\"" + SOAP_ENV
                + "\"><soap:Body>" + payload + "</soap:Body></soap:Envelope>";
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(readTimeout)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"" + op.soapAction() + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new SoapTransportException(SoapTransportException.Kind.TIMEOUT,
                    "SOAP endpoint did not answer in time (" + e.getClass().getSimpleName() + ")");
        } catch (ConnectException e) {
            throw new SoapTransportException(SoapTransportException.Kind.CONNECT, "SOAP endpoint refused the connection");
        } catch (IOException e) {
            throw new SoapTransportException(SoapTransportException.Kind.CONNECT,
                    "SOAP endpoint could not be reached (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SoapTransportException(SoapTransportException.Kind.CONNECT, "SOAP call interrupted");
        }

        int status = response.statusCode();
        Element payloadElement = bodyPayload(response.body(), status);
        if (payloadElement != null && SOAP_ENV.equals(payloadElement.getNamespaceURI())
                && "Fault".equals(payloadElement.getLocalName())) {
            throw new SoapFaultException(childText(payloadElement, "faultcode"), childText(payloadElement, "faultstring"));
        }
        if (status != 200) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP endpoint returned HTTP " + status + " without a SOAP Fault");
        }
        if (payloadElement == null) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE, "SOAP response has an empty soap:Body");
        }
        try {
            return converter.toJson(op.output(), payloadElement);
        } catch (ConversionException e) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP response does not match the WSDL: " + e.getMessage());
        }
    }

    private static Element bodyPayload(byte[] bytes, int status) {
        Document doc;
        try {
            doc = RuntimeXml.parse(bytes);
        } catch (SAXException e) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP endpoint returned HTTP " + status + " with a body that is not valid XML");
        }
        Element env = doc.getDocumentElement();
        if (!SOAP_ENV.equals(env.getNamespaceURI()) || !"Envelope".equals(env.getLocalName())) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP endpoint returned HTTP " + status + " without a SOAP 1.1 Envelope");
        }
        Element body = null;
        for (Node n = env.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && SOAP_ENV.equals(e.getNamespaceURI()) && "Body".equals(e.getLocalName())) {
                body = e;
                break;
            }
        }
        if (body == null) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE, "SOAP response has no soap:Body");
        }
        for (Node n = body.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) return e;
        }
        return null;
    }

    /** faultcode/faultstring are unqualified in SOAP 1.1, but some servers qualify them; match by local name. */
    private static String childText(Element parent, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName())) return e.getTextContent().trim();
        }
        return "";
    }
}
