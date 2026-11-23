package dev.wsdlforge.generators;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import dev.wsdlforge.core.ModelJson;
import dev.wsdlforge.core.ParseResult;
import dev.wsdlforge.core.WsdlParser;
import dev.wsdlforge.runtime.JsonXmlConverter;
import dev.wsdlforge.runtime.SoapClient;
import dev.wsdlforge.runtime.SoapFaultException;
import dev.wsdlforge.runtime.model.BuiltIn;
import dev.wsdlforge.runtime.model.ElementRef;
import dev.wsdlforge.runtime.model.FieldModel;
import dev.wsdlforge.runtime.model.OperationModel;
import dev.wsdlforge.runtime.model.ServiceModel;
import dev.wsdlforge.runtime.model.SoapVersion;
import dev.wsdlforge.runtime.model.TypeModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generators spec, Requirement 8 (in-browser preview). */
@Tag("fast")
class PreviewGeneratorTest {
    private static final ParseResult BANK = new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl"));
    /**
     * Reads the sample request the way the generated proxy's Jackson does: decimals
     * stay BigDecimal.
     */
    private static final ObjectMapper EXACT = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final String DECIMAL = "1234567890.123456789";

    private static JsonNode preview(ParseResult result) throws Exception {
        return ModelJson.mapper().readTree(new Generator().generate(result).text("preview.json"));
    }

    private static JsonNode sample(JsonNode preview, String operation) {
        for (JsonNode s : preview.get("samples")) {
            if (s.get("operation").asText().equals(operation))
                return s;
        }
        throw new AssertionError("no sample for " + operation);
    }

    @Test
    void req8_1_bankPreviewCarriesOpenApiMcpAndOneSamplePerOperation() throws Exception {
        JsonNode p = preview(BANK);
        assertThat(p.get("format").asInt()).isEqualTo(PreviewGenerator.FORMAT);
        assertThat(p.get("service").asText()).isEqualTo("LegacyBankService");
        assertThat(p.get("soapVersion").asText()).isEqualTo("1.1");
        assertThat(p.get("openapi").get("openapi").asText()).isEqualTo("3.0.3");
        assertThat(p.get("openapi").get("paths").fieldNames()).toIterable()
                .containsExactly("/GetBalance", "/Transfer", "/GetStatement");
        assertThat(p.get("samples")).hasSize(3);
        assertThat(p.get("samples")).allSatisfy(s -> assertThat(s.has("envelope")).isTrue());
    }

    @Test
    void req8_2_openApiJsonIsTheSameDocumentAsTheYaml() throws Exception {
        GeneratedFiles files = new Generator().generate(BANK);
        JsonNode fromYaml = ModelJson.mapper().valueToTree(
                new org.yaml.snakeyaml.Yaml().load(files.text("openapi.yaml")));
        JsonNode fromPreview = ModelJson.mapper().readTree(files.text("preview.json")).get("openapi");
        assertThat(fromPreview).isEqualTo(fromYaml);
    }

    @Test
    void req8_3_transferSampleKeepsEveryDecimalDigitInTheEnvelope() throws Exception {
        JsonNode transfer = sample(preview(BANK), "Transfer");
        assertThat(transfer.get("request").asText()).contains("\"amount\": " + DECIMAL);
        assertThat(transfer.get("envelope").asText())
                .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap:Envelope xmlns:soap=\""
                        + SoapVersion.SOAP_11.envelopeNamespace() + "\">")
                .contains(":amount>" + DECIMAL + "</");
        assertThat(transfer.get("decimalPaths")).extracting(JsonNode::asText).containsExactly("$.amount");
        assertThat(transfer.get("soapHeaders").get("SOAPAction").asText())
                .isEqualTo("\"urn:example:bank:v1:Transfer\"");
        assertThat(transfer.get("soapHeaders").get("Content-Type").asText()).isEqualTo("text/xml; charset=utf-8");
    }

    /**
     * The point of the preview: these are the bytes the generated proxy's
     * SoapClient really sends.
     */
    @Test
    void req8_4_envelopeAndHeadersMatchWhatSoapClientSends() throws Exception {
        for (SoapVersion version : SoapVersion.values()) {
            ServiceModel model = withVersion(BANK.model(), version);
            JsonNode transfer = sample(preview(new ParseResult(model, List.of())), "Transfer");
            Captured sent = send(model, "Transfer", transfer.get("request").asText());
            assertThat(sent.body()).as(version.label()).isEqualTo(transfer.get("envelope").asText());
            transfer.get("soapHeaders").fields().forEachRemaining(h -> assertThat(sent.headers().get(h.getKey()))
                    .as(version.label() + " " + h.getKey()).isEqualTo(h.getValue().asText()));
        }
    }

    @Test
    void req8_4_soap12SampleUsesTheSoap12EnvelopeAndActionParameter() throws Exception {
        JsonNode transfer = sample(preview(new ParseResult(withVersion(BANK.model(), SoapVersion.SOAP_12),
                List.of())), "Transfer");
        assertThat(transfer.get("envelope").asText()).contains(SoapVersion.SOAP_12.envelopeNamespace())
                .contains(":amount>" + DECIMAL + "</");
        assertThat(transfer.get("soapHeaders").has("SOAPAction")).isFalse();
        assertThat(transfer.get("soapHeaders").get("Content-Type").asText())
                .isEqualTo("application/soap+xml; charset=utf-8; action=\"urn:example:bank:v1:Transfer\"");
    }

    @Test
    void req8_1_mcpEntryMatchesTheGeneratedReadme() throws Exception {
        GeneratedFiles files = new Generator().generate(BANK);
        JsonNode mcp = ModelJson.mapper().readTree(files.text("preview.json")).get("mcp");
        String key = mcp.get("serverKey").asText();
        JsonNode server = mcp.get("kiroConfig").get("mcpServers").get(key);
        assertThat(files.text("mcp-server/README.md"))
                .contains("\"" + key + "\": {")
                .contains(server.get("args").get(1).asText())
                .contains("\"PROXY_URL\": \"" + server.get("env").get("PROXY_URL").asText() + "\"");
        assertThat(files.files()).containsKey("mcp-server/pom.xml");
    }

    @Test
    void req8_5_requiredFanOutIsOmittedNotBuilt() throws Exception {
        // T0 -> two required T1 -> two required T2 ... : 2^25 values if it were built.
        String ns = "urn:fan";
        Map<String, TypeModel> types = new LinkedHashMap<>();
        for (int i = 0; i < 25; i++) {
            types.put("T" + i, TypeModel.complex("T" + i, List.of(new FieldModel("a", ns, "T" + (i + 1), 1, 1),
                    new FieldModel("b", ns, "T" + (i + 1), 1, 1))));
        }
        types.put("T25", TypeModel.complex("T25", List.of(new FieldModel("v", ns, "xs:string", 1, 1))));
        types.put("xs:string", TypeModel.builtIn(BuiltIn.STRING));
        ElementRef ref = new ElementRef(ns, "Root", "T0");
        ServiceModel fan = new ServiceModel("Fan", ns, null, List.of(new OperationModel("Op", "", "", ref, ref)),
                types);
        long start = System.nanoTime();
        JsonNode s = sample(preview(new ParseResult(fan, List.of())), "Op");
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isLessThan(2_000);
        assertThat(s.has("envelope")).isFalse();
        assertThat(s.get("omitted").asText()).contains("more than " + SampleRequests.MAX_NODES);
    }

    @Test
    void req8_5_optionalRecursionGetsAFiniteSample() throws Exception {
        String ns = "urn:tree";
        Map<String, TypeModel> types = new LinkedHashMap<>();
        types.put("Node", TypeModel.complex("Node", List.of(new FieldModel("label", ns, "xs:string", 1, 1),
                new FieldModel("child", ns, "Node", 0, FieldModel.UNBOUNDED))));
        types.put("xs:string", TypeModel.builtIn(BuiltIn.STRING));
        ElementRef ref = new ElementRef(ns, "Tree", "Node");
        ServiceModel tree = new ServiceModel("Trees", ns, null, List.of(new OperationModel("Grow", "", "", ref, ref)),
                types);
        JsonNode s = sample(preview(new ParseResult(tree, List.of())), "Grow");
        assertThat(s.get("request").asText()).isEqualTo("{\n  \"label\": \"example-1\"\n}\n");
        assertThat(s.get("envelope").asText()).contains(">example-1</");
    }

    @Test
    void req8_5_previewNeverMakesAGenerationFail() throws Exception {
        GeneratedFiles full = new Generator().generate(BANK);
        long withoutPreview = full.totalBytes() - full.files().get("preview.json").length;
        // Room for every other file and a short note, not for the full preview.
        GeneratedFiles tight = new Generator(withoutPreview + 600, Generator.DEFAULT_MAX_TOOL_DEFINITIONS)
                .generate(BANK);
        JsonNode p = ModelJson.mapper().readTree(tight.text("preview.json"));
        assertThat(p.get("truncated").asText()).contains("size limit");
        assertThat(p.has("samples")).isFalse();
        // Without even that much room, the zip is still produced, just without a
        // preview.
        GeneratedFiles none = new Generator(withoutPreview, Generator.DEFAULT_MAX_TOOL_DEFINITIONS).generate(BANK);
        assertThat(none.files()).doesNotContainKey("preview.json");
        assertThat(none.files().keySet()).containsAll(new ArrayList<>(full.files().keySet()).stream()
                .filter(f -> !f.equals("preview.json")).toList());
    }

    @Test
    void sampleJsonEscapesHostileText() {
        String json = SampleRequests.toJson(Map.of("k\"\\\n\u2028", List.of("<\u0001>", Map.of())));
        assertThat(json).isEqualTo("{\n  \"k\\\"\\\\\\n\\u2028\": [\n    \"<\\u0001>\",\n    {}\n  ]\n}\n");
    }

    // ---------------------------------------------------------------- helpers

    private static ServiceModel withVersion(ServiceModel m, SoapVersion version) {
        return new ServiceModel(m.name(), m.targetNamespace(), m.endpointUrl(), version, m.operations(), m.types());
    }

    private record Captured(String body, Map<String, String> headers) {
    }

    /**
     * Runs the runtime's SoapClient with the sample request against a stub that
     * records the request.
     */
    private static Captured send(ServiceModel model, String operation, String requestJson) throws Exception {
        AtomicReference<Captured> seen = new AtomicReference<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ex -> {
            Map<String, String> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
            seen.set(new Captured(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), headers));
            byte[] fault = fault(model.soapVersion()).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", model.soapVersion().mediaType() + "; charset=utf-8");
            ex.sendResponseHeaders(500, fault.length);
            ex.getResponseBody().write(fault);
            ex.close();
        });
        stub.start();
        try {
            SoapClient client = new SoapClient(new JsonXmlConverter(model),
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/ws"), Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
            Object json = EXACT.readValue(requestJson, Object.class);
            assertThatThrownBy(() -> client.call(model.operation(operation), json))
                    .isInstanceOf(SoapFaultException.class);
        } finally {
            stub.stop(0);
        }
        // HttpServer normalizes header names to "Content-type"; compare
        // case-insensitively.
        Map<String, String> byCanonicalName = new LinkedHashMap<>();
        seen.get().headers().forEach((k, v) -> {
            for (String name : List.of("Content-Type", "SOAPAction")) {
                if (name.equalsIgnoreCase(k))
                    byCanonicalName.put(name, v);
            }
        });
        return new Captured(seen.get().body(), byCanonicalName);
    }

    private static String fault(SoapVersion v) {
        String ns = v.envelopeNamespace();
        String body = v == SoapVersion.SOAP_11
                ? "<soap:Fault><faultcode>soap:Server</faultcode><faultstring>stub</faultstring></soap:Fault>"
                : "<soap:Fault><soap:Code><soap:Value>soap:Receiver</soap:Value></soap:Code>"
                        + "<soap:Reason><soap:Text xml:lang=\"en\">stub</soap:Text></soap:Reason></soap:Fault>";
        return "<soap:Envelope xmlns:soap=\"" + ns + "\"><soap:Body>" + body + "</soap:Body></soap:Envelope>";
    }
}
