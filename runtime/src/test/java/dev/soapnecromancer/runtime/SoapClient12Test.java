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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generators spec, Requirements 2.4 and 2.6 for SOAP 1.2 bindings. */
@Tag("fast")
class SoapClient12Test {
    private static final String ENV12 = "http://www.w3.org/2003/05/soap-envelope";
    private static final String NS = "urn:example:bank:v1";
    private static final ElementRef IN = new ElementRef(NS, "TransferRequest", "TransferRequest");
    private static final ElementRef OUT = new ElementRef(NS, "TransferResponse", "TransferResponse");
    private static final OperationModel OP = new OperationModel("Transfer", "urn:example:bank:v1:Transfer", "", IN, OUT);
    private static final OperationModel NO_ACTION = new OperationModel("Plain", "", "", IN, OUT);
    private static final ServiceModel MODEL = new ServiceModel("Bank", NS, null, SoapVersion.SOAP_12,
            List.of(OP, NO_ACTION), Map.of(
                    "TransferRequest", TypeModel.complex("TransferRequest",
                            List.of(new FieldModel("amount", NS, "xs:decimal", 1, 1))),
                    "TransferResponse", TypeModel.complex("TransferResponse",
                            List.of(new FieldModel("newBalance", NS, "xs:decimal", 1, 1))),
                    "xs:decimal", TypeModel.builtIn(BuiltIn.DECIMAL)));

    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedType = new AtomicReference<>();
    private final AtomicReference<String> receivedSoapAction = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ws", ex -> {
            receivedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            receivedSoapAction.set(ex.getRequestHeaders().getFirst("SOAPAction"));
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0)
                ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SoapClient client() {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ws");
        return new SoapClient(new JsonXmlConverter(MODEL), uri, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private static String envelope(String body) {
        return "<env:Envelope xmlns:env=\"" + ENV12 + "\"><env:Body>" + body + "</env:Body></env:Envelope>";
    }

    private static String fault(String code, String subcode, String reasons) {
        return envelope("<env:Fault><env:Code><env:Value>" + code + "</env:Value>"
                + (subcode == null ? "" : "<env:Subcode><env:Value xmlns:app=\"urn:app\">" + subcode + "</env:Value></env:Subcode>")
                + "</env:Code><env:Reason>" + reasons + "</env:Reason></env:Fault>");
    }

    @Test
    void req2_4_sendsA12EnvelopeWithTheActionInTheContentType() {
        reply = envelope("<b:TransferResponse xmlns:b=\"" + NS + "\"><b:newBalance>-1234567740.123456789</b:newBalance></b:TransferResponse>");
        Object out = client().call(OP, Map.of("amount", new BigDecimal("1234567890.123456789")));

        assertThat(out).isEqualTo(Map.of("newBalance", new BigDecimal("-1234567740.123456789")));
        assertThat(receivedType.get())
                .isEqualTo("application/soap+xml; charset=utf-8; action=\"urn:example:bank:v1:Transfer\"");
        assertThat(receivedSoapAction.get()).as("SOAP 1.2 has no SOAPAction header").isNull();
        assertThat(receivedBody.get()).contains("<soap:Envelope xmlns:soap=\"" + ENV12 + "\"><soap:Body>");
    }

    @Test
    void req2_4_emptyActionSendsNoActionParameter() {
        reply = envelope("<b:TransferResponse xmlns:b=\"" + NS + "\"><b:newBalance>1</b:newBalance></b:TransferResponse>");
        client().call(NO_ACTION, Map.of("amount", 1));
        assertThat(receivedType.get()).isEqualTo("application/soap+xml; charset=utf-8");
    }

    /** The SOAP 1.2 HTTP binding sends Sender faults with HTTP 400. */
    @Test
    void req2_6_senderFaultOn400IsAClientFaultWithTheEnglishReason() {
        status = 400;
        reply = fault("env:Sender", "app:InsufficientFunds",
                "<env:Text xml:lang=\"de\">Deckung fehlt</env:Text><env:Text xml:lang=\"en-US\">Insufficient funds</env:Text>");
        assertThatThrownBy(() -> client().call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapFaultException.class, f -> {
                    assertThat(f.isClientFault()).isTrue();
                    assertThat(f.faultCode()).isEqualTo("env:Sender");
                    assertThat(f.faultString()).isEqualTo("Insufficient funds");
                });
    }

    @Test
    void req2_6_receiverAndOtherCodesAreServerFaults() {
        status = 500;
        for (String code : List.of("env:Receiver", "env:MustUnderstand", "env:VersionMismatch")) {
            reply = fault(code, null, "<env:Text xml:lang=\"fr\">en panne</env:Text>");
            assertThatThrownBy(() -> client().call(OP, Map.of("amount", 1))).as(code)
                    .isInstanceOfSatisfying(SoapFaultException.class, f -> {
                        assertThat(f.isClientFault()).isFalse();
                        assertThat(f.faultString()).as("first Text when there is no English one").isEqualTo("en panne");
                    });
        }
    }

    @Test
    void req2_6_senderInAnotherNamespaceIsNotTheCaller() {
        status = 400;
        reply = envelope("<env:Fault xmlns:x=\"urn:x\"><env:Code><env:Value>x:Sender</env:Value></env:Code>"
                + "<env:Reason><env:Text xml:lang=\"en\">no</env:Text></env:Reason></env:Fault>");
        assertThatThrownBy(() -> client().call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapFaultException.class, f -> assertThat(f.isClientFault()).isFalse());
    }

    @Test
    void req2_4_aSoap11AnswerToASoap12CallIsABadResponse() {
        reply = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>";
        assertThatThrownBy(() -> client().call(OP, Map.of("amount", 1)))
                .isInstanceOfSatisfying(SoapTransportException.class, e -> {
                    assertThat(e.kind()).isEqualTo(SoapTransportException.Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).contains("SOAP 1.1 envelope").contains("binding is SOAP 1.2");
                });
    }

    @Test
    void aModelWithoutAVersionIsSoap11() {
        ServiceModel legacy = new ServiceModel("Bank", NS, null, null, List.of(OP), MODEL.types());
        assertThat(legacy.soapVersion()).isEqualTo(SoapVersion.SOAP_11);
        assertThat(new SoapClient(new JsonXmlConverter(legacy), URI.create("http://127.0.0.1:1/"), Duration.ofSeconds(1),
                Duration.ofSeconds(1)).version()).isEqualTo(SoapVersion.SOAP_11);
    }
}
