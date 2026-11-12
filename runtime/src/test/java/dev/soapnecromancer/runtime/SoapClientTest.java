package dev.soapnecromancer.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.soapnecromancer.runtime.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generators spec, Requirements 2.4, 2.6, 2.9: SoapClient against a stub HTTP server. */
@Tag("fast")
class SoapClientTest {
    private static final String NS = "urn:example:bank:v1";
    private static final ElementRef IN = new ElementRef(NS, "TransferRequest", "TransferRequest");
    private static final ElementRef OUT = new ElementRef(NS, "TransferResponse", "TransferResponse");
    private static final OperationModel OP = new OperationModel("Transfer", "urn:example:bank:v1:Transfer", "", IN, OUT);
    private static final ServiceModel MODEL = new ServiceModel("Bank", NS, null, List.of(OP), Map.of(
            "TransferRequest", TypeModel.complex("TransferRequest", List.of(new FieldModel("amount", NS, "xs:decimal", 1, 1))),
            "TransferResponse", TypeModel.complex("TransferResponse", List.of(new FieldModel("newBalance", NS, "xs:decimal", 1, 1))),
            "xs:decimal", TypeModel.builtIn(BuiltIn.DECIMAL)));

    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedAction = new AtomicReference<>();
    private final AtomicReference<String> receivedType = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "";
    private volatile long delayMs = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ws", ex -> {
            receivedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAction.set(ex.getRequestHeaders().getFirst("SOAPAction"));
            receivedType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SoapClient client(Duration readTimeout) {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ws");
        return new SoapClient(new JsonXmlConverter(MODEL), uri, Duration.ofSeconds(2), readTimeout);
    }

    private static String envelope(String body) {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" + body + "</s:Body></s:Envelope>";
    }

    @Test
    void req2_4_sendsEnvelopeWithSoapActionAndReturnsExactJson() {
        reply = envelope("<b:TransferResponse xmlns:b=\"" + NS + "\"><b:newBalance>-1234567740.123456789</b:newBalance></b:TransferResponse>");
        Object out = client(Duration.ofSeconds(5)).call(OP, Map.of("amount", new BigDecimal("1234567890.123456789")));

        assertThat(out).isEqualTo(Map.of("newBalance", new BigDecimal("-1234567740.123456789")));
        assertThat(receivedAction.get()).isEqualTo("\"urn:example:bank:v1:Transfer\"");
        assertThat(receivedType.get()).isEqualTo("text/xml; charset=utf-8");
        assertThat(receivedBody.get())
                .contains("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>")
                .contains("<ns1:amount>1234567890.123456789</ns1:amount>");
    }

    @Test
    void req2_6_requestConversionErrorIsRaisedBeforeSending() {
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).call(OP, Map.of("amount", "lots")))
                .isInstanceOf(ConversionException.class);
        assertThat(receivedBody.get()).isNull();
    }

    @Test
    void req2_6_clientAndServerFaults() {
        status = 500;
        reply = envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>Insufficient funds</faultstring></s:Fault>");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapFaultException.class, f -> {
                    assertThat(f.isClientFault()).isTrue();
                    assertThat(f.faultString()).isEqualTo("Insufficient funds");
                });

        reply = envelope("<s:Fault><faultcode>s:Server.Database</faultcode><faultstring>down</faultstring></s:Fault>");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapFaultException.class, f -> assertThat(f.isClientFault()).isFalse());
    }

    @Test
    void req2_6_req2_7_nonSoapErrorPageIsBadResponseWithoutLeakingBody() {
        status = 503;
        reply = "<html><body>secret internal stack trace</body></html>";
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class, e -> {
                    assertThat(e.kind()).isEqualTo(SoapTransportException.Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).contains("HTTP 503").doesNotContain("secret");
                });
    }

    @Test
    void req2_6_responseThatDoesNotMatchTheWsdlIsBadResponse() {
        reply = envelope("<b:Other xmlns:b=\"" + NS + "\"/>");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(SoapTransportException.Kind.BAD_RESPONSE));
    }

    @Test
    void responseWithDoctypeIsRejected() {
        reply = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + envelope("&e;");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(SoapTransportException.Kind.BAD_RESPONSE));
    }

    @Test
    void req2_6_slowEndpointIsTimeout() {
        delayMs = 1500;
        reply = envelope("<b:TransferResponse xmlns:b=\"" + NS + "\"><b:newBalance>1</b:newBalance></b:TransferResponse>");
        assertThatThrownBy(() -> client(Duration.ofMillis(200)).call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(SoapTransportException.Kind.TIMEOUT));
    }

    @Test
    void req2_6_closedPortIsConnect() throws IOException {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        SoapClient c = new SoapClient(new JsonXmlConverter(MODEL), URI.create("http://127.0.0.1:" + closed + "/ws"),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertThatThrownBy(() -> c.call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(SoapTransportException.Kind.CONNECT));
    }
}
