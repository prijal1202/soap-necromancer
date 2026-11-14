package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import dev.soapnecromancer.runtime.model.SoapVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SOAP 1.1 and 1.2 document/literal client used by every generated proxy. JDK
 * only (HttpClient +
 * DOM). The version is the model's ({@link ServiceModel#soapVersion()}).
 * Generators spec,
 * Requirements 2.4, 2.6, 2.9-2.12.
 *
 * <p>
 * Request JSON errors surface as {@link ConversionException} before anything is
 * sent. A SOAP
 * Fault becomes {@link SoapFaultException}; every other failure becomes
 * {@link SoapTransportException}. Every call is bounded in time (the read
 * timeout covers the whole
 * response, body included) and in size ({@code maxResponseBytes}), whatever the
 * backend does.
 */
public final class SoapClient {
    /** The SOAP 1.1 envelope namespace. */
    public static final String SOAP_ENV = SoapVersion.SOAP_11.envelopeNamespace();
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private final SoapVersion version;
    private final JsonXmlConverter converter;
    private final URI endpoint;
    private final Duration readTimeout;
    private final int maxResponseBytes;
    private final HttpClient http;

    public SoapClient(JsonXmlConverter converter, URI endpoint, Duration connectTimeout, Duration readTimeout) {
        this(converter, endpoint, connectTimeout, readTimeout, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public SoapClient(JsonXmlConverter converter, URI endpoint, Duration connectTimeout, Duration readTimeout,
            int maxResponseBytes) {
        this.converter = Objects.requireNonNull(converter);
        this.version = converter.model().soapVersion();
        this.endpoint = Objects.requireNonNull(endpoint);
        String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || endpoint.getHost() == null) {
            throw new IllegalArgumentException("SOAP endpoint must be an http(s) URL with a host: " + endpoint);
        }
        this.readTimeout = Objects.requireNonNull(readTimeout);
        if (maxResponseBytes <= 0)
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // legacy servers often mishandle the h2c upgrade
                .connectTimeout(Objects.requireNonNull(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public URI endpoint() {
        return endpoint;
    }

    public SoapVersion version() {
        return version;
    }

    /**
     * Converts {@code json}, calls the operation, and returns the response payload
     * as canonical JSON.
     */
    public Object call(OperationModel op, Object json) {
        String payload = converter.toXml(op.input(), json);
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap:Envelope xmlns:soap=\""
                + version.envelopeNamespace() + "\"><soap:Body>" + payload + "</soap:Body></soap:Envelope>";
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(readTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8));
        switch (version) {
            case SOAP_11 -> request.header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", quotedHeaderValue(op.soapAction()));
            // SOAP 1.2 HTTP binding: the action travels as a media-type parameter, not a
            // header.
            case SOAP_12 -> request.header("Content-Type", "application/soap+xml; charset=utf-8"
                    + (op.soapAction().isEmpty() ? "" : "; action=" + quotedHeaderValue(op.soapAction())));
        }

        HttpResponse<byte[]> response = send(request.build());
        int status = response.statusCode();
        Element payloadElement = bodyPayload(response.body(), charsetOf(response), status);
        if (payloadElement != null && version.envelopeNamespace().equals(payloadElement.getNamespaceURI())
                && "Fault".equals(payloadElement.getLocalName())) {
            throw switch (version) {
                case SOAP_11 -> fault11(payloadElement);
                case SOAP_12 -> fault12(payloadElement);
            };
        }
        if (status != 200) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP endpoint returned HTTP " + status + " without a SOAP Fault");
        }
        if (payloadElement == null) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP response has an empty soap:Body");
        }
        try {
            return converter.toJson(op.output(), payloadElement);
        } catch (ConversionException e) {
            // The path only: the converter's message may quote backend text (Requirement
            // 2.7).
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP response does not match the WSDL at " + e.path());
        }
    }

    /**
     * One deadline for the whole exchange: {@code HttpRequest.timeout} stops
     * applying once
     * headers arrive, so a backend trickling its body would otherwise hold the
     * thread forever.
     */
    private HttpResponse<byte[]> send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request,
                info -> new LimitedBody(maxResponseBytes,
                        info.headers().firstValueAsLong("Content-Length").orElse(-1)));
        try {
            return future.get(readTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SoapTransportException(SoapTransportException.Kind.TIMEOUT,
                    "SOAP endpoint did not answer in time (" + readTimeout.toMillis() + " ms)");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SoapTransportException(SoapTransportException.Kind.CONNECT, "SOAP call interrupted");
        } catch (ExecutionException e) {
            throw transportFailure(e.getCause());
        }
    }

    private SoapTransportException transportFailure(Throwable cause) {
        // HttpClient wraps the real cause one or two levels deep.
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof ResponseTooLarge) {
                return new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                        "SOAP response is larger than " + maxResponseBytes + " bytes");
            }
            if (t instanceof HttpTimeoutException) {
                return new SoapTransportException(SoapTransportException.Kind.TIMEOUT,
                        "SOAP endpoint did not answer in time (" + t.getClass().getSimpleName() + ")");
            }
            if (t instanceof ConnectException) {
                return new SoapTransportException(SoapTransportException.Kind.CONNECT,
                        "SOAP endpoint refused the connection");
            }
        }
        String type = cause instanceof IOException ? cause.getClass().getSimpleName() : "unexpected error";
        return new SoapTransportException(SoapTransportException.Kind.CONNECT,
                "SOAP endpoint could not be reached (" + type + ")");
    }

    /**
     * Collects the body up to {@code max} bytes; one byte more cancels the
     * download.
     */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;
        private final long declaredLength;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBody(int max, long declaredLength) {
            this.max = max;
            this.declaredLength = declaredLength;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (declaredLength > max) {
                s.cancel();
                result.completeExceptionally(new ResponseTooLarge());
            } else {
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone())
                return;
            for (ByteBuffer b : items) {
                if ((long) out.size() + b.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLarge());
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    private static final class ResponseTooLarge extends IOException {
        ResponseTooLarge() {
            super("response too large", null);
        }
    }

    /**
     * Requirement 2.11: the Content-Type charset, when present and known, decides
     * the decoding.
     */
    private static Charset charsetOf(HttpResponse<?> response) {
        String type = response.headers().firstValue("Content-Type").orElse("");
        for (String param : type.split(";")) {
            String p = param.strip();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                String name = p.substring(8).strip().replace("\"", "");
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException unknown) {
                    return null; // let the parser use the XML declaration
                }
            }
        }
        return null;
    }

    private Element bodyPayload(byte[] bytes, Charset charset, int status) {
        Document doc;
        try {
            doc = RuntimeXml.parse(bytes, charset);
        } catch (SAXException e) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP endpoint returned HTTP " + status + " with a body that is not valid XML");
        }
        Element env = doc.getDocumentElement();
        String ns = version.envelopeNamespace();
        if (!"Envelope".equals(env.getLocalName()) || !ns.equals(env.getNamespaceURI())) {
            SoapVersion other = "Envelope".equals(env.getLocalName())
                    ? SoapVersion.ofEnvelopeNamespace(env.getNamespaceURI())
                    : null;
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE, other != null
                    ? "SOAP endpoint answered with a SOAP " + other.label() + " envelope; the WSDL binding is SOAP "
                            + version.label()
                    : "SOAP endpoint returned HTTP " + status + " without a SOAP " + version.label() + " Envelope");
        }
        Element body = child(env, ns, "Body");
        if (body == null) {
            throw new SoapTransportException(SoapTransportException.Kind.BAD_RESPONSE,
                    "SOAP response has no soap:Body");
        }
        for (Node n = body.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e)
                return e;
        }
        return null;
    }

    /**
     * faultcode is a QName: only the envelope namespace's {@code Client} (or
     * {@code Client.*}) is
     * the caller's fault; {@code app:Client} in an application namespace is not. An
     * unprefixed
     * {@code Client} is accepted too, because sloppy servers send it.
     */
    private static SoapFaultException fault11(Element fault) {
        Element codeEl = childByLocalName(fault, "faultcode");
        String code = textOf(codeEl);
        String local = code.substring(code.indexOf(':') + 1);
        boolean client = inEnvelopeNamespace(codeEl, code, SOAP_ENV)
                && (local.equals("Client") || local.startsWith("Client."));
        return new SoapFaultException(code, textOf(childByLocalName(fault, "faultstring")), client);
    }

    /**
     * SOAP 1.2 (part 1, 5.4): {@code Code/Value} is one of the envelope namespace's
     * Sender,
     * Receiver, MustUnderstand, VersionMismatch or DataEncodingUnknown; only Sender
     * blames the
     * caller. {@code Reason} holds one {@code Text} per language: English is
     * preferred.
     */
    private static SoapFaultException fault12(Element fault) {
        String ns = SoapVersion.SOAP_12.envelopeNamespace();
        Element code = child(fault, ns, "Code");
        Element valueEl = code == null ? null : child(code, ns, "Value");
        String value = textOf(valueEl);
        boolean client = inEnvelopeNamespace(valueEl, value, ns)
                && value.substring(value.indexOf(':') + 1).equals("Sender");
        return new SoapFaultException(value, reason(child(fault, ns, "Reason"), ns), client);
    }

    private static String reason(Element reason, String ns) {
        if (reason == null)
            return "";
        Element first = null;
        for (Node n = reason.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && "Text".equals(e.getLocalName())) {
                String lang = e.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang").toLowerCase(Locale.ROOT);
                if (lang.equals("en") || lang.startsWith("en-"))
                    return textOf(e);
                if (first == null)
                    first = e;
            }
        }
        return textOf(first);
    }

    /**
     * A fault code is a QName. It is the envelope's own code when its prefix
     * resolves to the
     * envelope namespace, or when it has no prefix at all (sloppy servers send bare
     * {@code Client}).
     */
    private static boolean inEnvelopeNamespace(Element codeEl, String code, String envelopeNs) {
        int colon = code.indexOf(':');
        if (colon < 0)
            return true;
        return codeEl != null && envelopeNs.equals(codeEl.lookupNamespaceURI(code.substring(0, colon)));
    }

    /**
     * SOAPAction is a quoted string; the value comes from the WSDL, so it is
     * escaped and kept on one line.
     */
    public static String quotedHeaderValue(String value) {
        StringBuilder b = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\')
                b.append('\\').append(c);
            else if (c >= 0x20 && c < 0x7F)
                b.append(c);
            // control characters and non-ASCII cannot appear in a header value: dropped
        }
        return b.append('"').toString();
    }

    private static Element child(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName()))
                return e;
        }
        return null;
    }

    /**
     * faultcode/faultstring are unqualified in SOAP 1.1, but some servers qualify
     * them; match by local name.
     */
    private static Element childByLocalName(Element parent, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName()))
                return e;
        }
        return null;
    }

    private static String textOf(Element e) {
        return e == null ? "" : e.getTextContent().strip();
    }
}
