package dev.soapnecromancer.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.soapnecromancer.runtime.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generators spec, Requirements 2.7 and 2.10-2.12: every SOAP call is bounded, whatever the backend does. */
@Tag("fast")
class SoapClientHardeningTest {
    private static final String NS = "urn:t";
    private static final ElementRef IN = new ElementRef(NS, "Req", "Req");
    private static final ElementRef OUT = new ElementRef(NS, "Res", "Res");
    private static final OperationModel OP = new OperationModel("Op", "urn:t:\"quoted\"\r\nX-Injected: 1", "", IN, OUT);
    private static final ServiceModel MODEL = new ServiceModel("S", NS, null, List.of(OP), Map.of(
            "Req", TypeModel.complex("Req", List.of(new FieldModel("n", NS, "xs:int", 1, 1))),
            "Res", TypeModel.complex("Res", List.of(new FieldModel("text", NS, "xs:string", 1, 1),
                    new FieldModel("n", NS, "xs:int", 0, 1))),
            "xs:int", TypeModel.builtIn(BuiltIn.INT), "xs:string", TypeModel.builtIn(BuiltIn.STRING)));

    private HttpServer server;
    private final AtomicReference<String> receivedAction = new AtomicReference<>();
    private volatile int status = 200;
    private volatile byte[] reply = new byte[0];
    private volatile String replyType = "text/xml; charset=utf-8";
    /** Pause between sending the headers and the body. */
    private volatile long bodyDelayMs = 0;
    /** Repeat the reply this many times (a large body without holding it in the test). */
    private volatile int repeat = 1;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ws", ex -> {
            ex.getRequestBody().readAllBytes();
            receivedAction.set(ex.getRequestHeaders().getFirst("SOAPAction"));
            ex.getResponseHeaders().add("Content-Type", replyType);
            try {
                ex.sendResponseHeaders(status, repeat == 1 && bodyDelayMs == 0 ? reply.length : 0);
                OutputStream out = ex.getResponseBody();
                out.flush();
                if (bodyDelayMs > 0)
                    Thread.sleep(bodyDelayMs);
                for (int i = 0; i < repeat; i++)
                    out.write(reply);
            } catch (IOException | InterruptedException ignored) {
                // the client gave up: expected in these tests
            } finally {
                ex.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SoapClient client(Duration readTimeout, int maxBytes) {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ws");
        return new SoapClient(new JsonXmlConverter(MODEL), uri, Duration.ofSeconds(2), readTimeout, maxBytes);
    }

    private static byte[] envelope(String body, String charset) {
        return ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" + body
                + "</s:Body></s:Envelope>").getBytes(java.nio.charset.Charset.forName(charset));
    }

    private static String fault(String code, String text, String extraNs) {
        return "<s:Fault " + extraNs + "><faultcode>" + code + "</faultcode><faultstring>" + text
                + "</faultstring></s:Fault>";
    }

    @Test
    void req2_10_slowBodyAfterTheHeadersTimesOut() {
        bodyDelayMs = 3000;
        reply = envelope("<t:Res xmlns:t=\"urn:t\"><t:text>late</t:text></t:Res>", "UTF-8");
        long start = System.nanoTime();
        assertThatThrownBy(() -> client(Duration.ofMillis(300), 1 << 20).call(OP, Map.of("n", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(SoapTransportException.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void req2_10_oversizedResponseIsABadResponseNotAnOutOfMemory() {
        reply = "<!-- padding -->".repeat(1024).getBytes(StandardCharsets.UTF_8); // 16 KB per write
        repeat = 200; // about 3 MB, chunked: no Content-Length to reject up front
        assertThatThrownBy(() -> client(Duration.ofSeconds(5), 64 * 1024).call(OP, Map.of("n", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class, e -> {
                    assertThat(e.kind()).isEqualTo(SoapTransportException.Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).contains("larger than 65536 bytes");
                });
    }

    @Test
    void req2_10_declaredOversizedResponseIsRejectedUpFront() {
        reply = new byte[100_000];
        assertThatThrownBy(() -> client(Duration.ofSeconds(5), 64 * 1024).call(OP, Map.of("n", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class,
                        e -> assertThat(e.getMessage()).contains("larger than"));
    }

    @Test
    void req2_11_contentTypeCharsetDecidesTheDecoding() {
        replyType = "text/xml; charset=ISO-8859-1";
        reply = envelope("<t:Res xmlns:t=\"urn:t\"><t:text>caf\u00e9</t:text></t:Res>", "ISO-8859-1");
        assertThat(client(Duration.ofSeconds(5), 1 << 20).call(OP, Map.of("n", 1))).isEqualTo(Map.of("text", "caf\u00e9"));
    }

    @Test
    void req2_11_onlyTheSoapNamespacesClientIsAClientFault() {
        status = 500;
        reply = envelope(fault("app:Client", "an application code", "xmlns:app=\"urn:app\""), "UTF-8");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5), 1 << 20).call(OP, Map.of("n", 1)))
                .isInstanceOfSatisfying(SoapFaultException.class, f -> assertThat(f.isClientFault()).isFalse());

        reply = envelope(fault("s:Client.Validation", "rejected", ""), "UTF-8");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5), 1 << 20).call(OP, Map.of("n", 1)))
                .isInstanceOfSatisfying(SoapFaultException.class, f -> assertThat(f.isClientFault()).isTrue());

        reply = envelope(fault("Client", "sloppy but common", ""), "UTF-8");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5), 1 << 20).call(OP, Map.of("n", 1)))
                .isInstanceOfSatisfying(SoapFaultException.class, f -> assertThat(f.isClientFault()).isTrue());
    }

    @Test
    void req2_12_faultStringIsTruncated() {
        status = 500;
        reply = envelope(fault("s:Server", "x".repeat(5000), ""), "UTF-8");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5), 1 << 20).call(OP, Map.of("n", 1)))
                .isInstanceOfSatisfying(SoapFaultException.class,
                        f -> assertThat(f.faultString()).hasSize(SoapFaultException.MAX_FAULT_STRING + 3));
    }

    @Test
    void req2_7_mismatchedResponseNamesThePathButNotTheBackendText() {
        reply = envelope("<t:Res xmlns:t=\"urn:t\"><t:text>ok</t:text><t:n>java.lang.Error: secret detail</t:n></t:Res>",
                "UTF-8");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5), 1 << 20).call(OP, Map.of("n", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("SOAP response does not match the WSDL at $.n");
                });
    }

    /** soapAction comes from the WSDL: it must stay one quoted header value. */
    @Test
    void soapActionCannotInjectHeaders() {
        reply = envelope("<t:Res xmlns:t=\"urn:t\"><t:text>ok</t:text></t:Res>", "UTF-8");
        client(Duration.ofSeconds(5), 1 << 20).call(OP, Map.of("n", 1));
        assertThat(receivedAction.get()).isEqualTo("\"urn:t:\\\"quoted\\\"X-Injected: 1\"");
    }

    @Test
    void endpointMustBeHttpWithAHost() {
        assertThatThrownBy(() -> new SoapClient(new JsonXmlConverter(MODEL), URI.create("file:///etc/passwd"),
                Duration.ofSeconds(1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
