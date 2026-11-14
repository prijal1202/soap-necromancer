package dev.soapnecromancer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.soapnecromancer.core.SsrfCanary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Task 10 against the real HTTP stack (Tomcat on a random port), the way
 * Render runs it:
 * stateless zip responses, upload limits, SSRF and XXE through the public
 * endpoint, mock-bank in-process.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "necromancer.max-upload-bytes=65536",
        "spring.servlet.multipart.max-file-size=65536", "spring.servlet.multipart.max-request-size=65536",
        "spring.servlet.multipart.file-size-threshold=65536",
        "necromancer.rate-limit.generate-per-minute=10000", "necromancer.rate-limit.mock-bank-per-minute=10000" })
class GenerateApiTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpResponse<byte[]> postRaw(byte[] body, String contentType) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url("/api/generate")))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> postMultipart(String field, byte[] content) throws Exception {
        String boundary = "----necromancer" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
                + "\"; filename=\"service.wsdl\"\r\nContent-Type: text/xml\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        return HTTP.send(HttpRequest.newBuilder(URI.create(url("/api/generate")))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private byte[] sampleWsdl() throws Exception {
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url("/api/sample-wsdl"))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;)
                files.put(e.getName(), in.readAllBytes());
        }
        return files;
    }

    private JsonNode problem(HttpResponse<byte[]> r) throws IOException {
        assertThat(r.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
        return json.readTree(r.body());
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void multipartUploadReturnsTheZipDirectly() throws Exception {
        HttpResponse<byte[]> r = postMultipart("wsdl", sampleWsdl());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/zip");
        assertThat(r.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(v -> assertThat(v).contains("legacy-bank-service-resurrected.zip"));
        assertThat(r.headers().firstValue("X-Generator-Operations")).hasValue("3");
        assertThat(r.headers().firstValue("X-Generator-Warnings")).hasValue("0");
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");

        Map<String, byte[]> files = unzip(r.body());
        assertThat(files.keySet()).contains("model.json", "openapi.yaml", "generator-report.json", "README.md",
                "proxy/pom.xml", "proxy/src/main/resources/application.properties",
                "proxy/src/test/java/generated/legacybankservice/TransferContractTest.java",
                "mcp-server/pom.xml", "mcp-server/src/main/resources/tools.json");
    }

    @Test
    void rawXmlBodyWorksAndGenerationIsStateless() throws Exception {
        byte[] wsdl = sampleWsdl();
        HttpResponse<byte[]> a = postRaw(wsdl, "text/xml");
        HttpResponse<byte[]> b = postRaw(wsdl, "application/xml");
        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(b.statusCode()).isEqualTo(200);
        // Same input, same bytes: nothing about earlier requests leaks into later ones.
        assertThat(a.body()).isEqualTo(b.body());
    }

    /**
     * The sample WSDL advertises this app's own mock-bank, so the generated proxy
     * works out of the box.
     */
    @Test
    void sampleWsdlPointsTheGeneratedProxyAtThisAppsMockBank() throws Exception {
        String wsdl = new String(sampleWsdl(), StandardCharsets.UTF_8);
        assertThat(wsdl).contains("location=\"http://127.0.0.1:" + port + "/mock-bank/ws\"");

        Map<String, byte[]> files = unzip(postRaw(wsdl.getBytes(StandardCharsets.UTF_8), "text/xml").body());
        assertThat(new String(files.get("proxy/src/main/resources/application.properties"), StandardCharsets.UTF_8))
                .contains("soap.endpoint=${SOAP_ENDPOINT:http://127.0.0.1:" + port + "/mock-bank/ws}");
    }

    @Test
    void mockBankAnswersSoapInTheSameJvm() throws Exception {
        String envelope = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:b="urn:example:bank:v1">
                  <soap:Body><b:GetBalanceRequest><b:accountId>ACC-1001</b:accountId></b:GetBalanceRequest></soap:Body>
                </soap:Envelope>""";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url("/mock-bank/ws")))
                .header("Content-Type", "text/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(envelope)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("GetBalanceResponse").contains("ACC-1001");
    }

    /**
     * Bursts beyond max-concurrent queue for a slot instead of failing (measured: 4
     * of 8 got 503 before).
     */
    @Test
    void burstOfConcurrentUploadsAllSucceed() throws Exception {
        byte[] wsdl = sampleWsdl();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++)
                futures.add(pool.submit(() -> postRaw(wsdl, "text/xml").statusCode()));
            for (var f : futures)
                assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void healthEndpoint() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url("/api/health"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("UP");
    }

    // ------------------------------------------------------------------ hostile
    // input

    /**
     * The SSRF guarantee, now through the public upload: no fetch vector reaches
     * the network.
     */
    @Test
    void ssrfPayloadUploadFetchesNothing() throws Exception {
        try (SsrfCanary canary = new SsrfCanary()) {
            HttpResponse<byte[]> r = postMultipart("wsdl", canary.wsdl());
            assertThat(canary.hits()).as("requests the generator made to the canary").isEmpty();
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("X-Generator-Warnings")).hasValue("6");
            JsonNode report = json.readTree(unzip(r.body()).get("generator-report.json"));
            assertThat(report.get("warnings").toString()).contains("EXTERNAL_IMPORT").contains("169.254.169.254");
        }
    }

    @Test
    void xxeIsRejected() throws Exception {
        byte[] evil = ("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\">&x;</wsdl:definitions>")
                .getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> r = postRaw(evil, "text/xml");
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(problem(r).get("detail").asText()).contains("DOCTYPE").doesNotContain("root:");
    }

    @Test
    void unusableWsdlIs422WithItsWarnings() throws Exception {
        String soap12Only = new String(sampleWsdl(), StandardCharsets.UTF_8)
                .replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://schemas.xmlsoap.org/wsdl/soap12/");
        HttpResponse<byte[]> r = postRaw(soap12Only.getBytes(StandardCharsets.UTF_8), "text/xml");
        assertThat(r.statusCode()).isEqualTo(422);
        JsonNode p = problem(r);
        assertThat(p.get("title").asText()).isEqualTo("WSDL cannot be resurrected");
        assertThat(p.get("warnings").toString()).contains("SOAP12_BINDING");
    }

    // ------------------------------------------------------------------ generation
    // blow-up (422, never 500)

    /** A WSDL with n operations sharing one request type, built from a template. */
    static byte[] wsdlWithOperations(int n, String types, String requestType) {
        StringBuilder pt = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            pt.append("<wsdl:operation name=\"Op").append(i)
                    .append("\"><wsdl:input message=\"tns:In\"/><wsdl:output message=\"tns:Out\"/></wsdl:operation>");
            b.append("<wsdl:operation name=\"Op").append(i).append("\"><s:operation soapAction=\"urn:t:Op").append(i)
                    .append("\"/><wsdl:input><s:body use=\"literal\"/></wsdl:input><wsdl:output><s:body use=\"literal\"/></wsdl:output></wsdl:operation>");
        }
        String xml = """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:s="http://schemas.xmlsoap.org/wsdl/soap/"
                    xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:t" targetNamespace="urn:t">
                  <wsdl:types><xs:schema targetNamespace="urn:t" elementFormDefault="qualified">
                    %s
                    <xs:element name="Req" type="%s"/>
                    <xs:element name="Res"><xs:complexType><xs:sequence><xs:element name="ok" type="xs:boolean"/></xs:sequence></xs:complexType></xs:element>
                  </xs:schema></wsdl:types>
                  <wsdl:message name="In"><wsdl:part name="p" element="tns:Req"/></wsdl:message>
                  <wsdl:message name="Out"><wsdl:part name="p" element="tns:Res"/></wsdl:message>
                  <wsdl:portType name="PT">%s</wsdl:portType>
                  <wsdl:binding name="B" type="tns:PT"><s:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>%s</wsdl:binding>
                  <wsdl:service name="Huge"><wsdl:port name="P" binding="tns:B"><s:address location="http://legacy.example/ws"/></wsdl:port></wsdl:service>
                </wsdl:definitions>"""
                .formatted(types, requestType, pt, b);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static final String TREE = """
            <xs:complexType name="Node"><xs:sequence>
              <xs:element name="label" type="xs:string"/>
              <xs:element name="child" type="tns:Node" minOccurs="0" maxOccurs="unbounded"/>
            </xs:sequence></xs:complexType>""";

    private void assert422(HttpResponse<byte[]> r, String warningCode) throws IOException {
        assertThat(r.statusCode()).as(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo(422);
        JsonNode p = problem(r);
        assertThat(p.get("title").asText()).isEqualTo("WSDL cannot be resurrected");
        assertThat(p.get("warnings").toString()).contains(warningCode);
    }

    @Test
    void recursiveTypeIsSupported() throws Exception {
        HttpResponse<byte[]> r = postRaw(wsdlWithOperations(1, TREE, "tns:Node"), "text/xml");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(new String(unzip(r.body()).get("mcp-server/src/main/resources/tools.json"), StandardCharsets.UTF_8))
                .contains("#/$defs/Node");
    }

    @Test
    void tooManyOperationsIs422() throws Exception {
        assert422(postRaw(wsdlWithOperations(201, TREE, "tns:Node"), "text/xml"), "LIMIT_OPERATIONS");
    }

    @Test
    void requiredSelfReferenceIs422() throws Exception {
        String loop = "<xs:complexType name=\"Loop\"><xs:sequence><xs:element name=\"next\" type=\"tns:Loop\"/></xs:sequence></xs:complexType>";
        assert422(postRaw(wsdlWithOperations(1, loop, "tns:Loop"), "text/xml"), "UNSATISFIABLE_RECURSION");
    }

    @Test
    void deeplyNestedXmlIs422() throws Exception {
        String deep = "<a>".repeat(400) + "</a>".repeat(400);
        HttpResponse<byte[]> r = postRaw(deep.getBytes(StandardCharsets.UTF_8), "text/xml");
        assertThat(r.statusCode()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ behind
    // Render's proxy

    @Test
    void sampleWsdlHonoursForwardedHeaders() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url("/api/sample-wsdl")))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "soap-necromancer.onrender.com")
                .header("X-Forwarded-Port", "443")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.body()).contains("location=\"https://soap-necromancer.onrender.com/mock-bank/ws\"");
    }

    @Test
    void notXmlIs422() throws Exception {
        assertThat(postRaw("hello".getBytes(StandardCharsets.UTF_8), "text/xml").statusCode()).isEqualTo(422);
    }

    @Test
    void emptyUploadIs400() throws Exception {
        HttpResponse<byte[]> r = postRaw(new byte[0], "text/xml");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(problem(r).get("detail").asText()).contains("empty");
    }

    @Test
    void oversizedRawBodyIs413() throws Exception {
        HttpResponse<byte[]> r = postRaw(new byte[65537], "text/xml");
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(problem(r).get("detail").asText()).contains("65536");
    }

    @Test
    void oversizedMultipartIs413() throws Exception {
        HttpResponse<byte[]> r = postMultipart("wsdl", new byte[70_000]);
        assertThat(r.statusCode()).isEqualTo(413);
    }

    @Test
    void wrongMultipartFieldIs400AndWrongMediaTypeIs415() throws Exception {
        assertThat(postMultipart("file", sampleWsdl()).statusCode()).isEqualTo(400);
        HttpResponse<byte[]> wrongType = postRaw(sampleWsdl(), "application/json");
        assertThat(wrongType.statusCode()).isEqualTo(415);
        assertThat(problem(wrongType).get("detail").asText()).contains("multipart/form-data").contains("text/xml");
    }

    /** A cut-off multipart body used to surface as a 500. */
    @Test
    void malformedMultipartIs400() throws Exception {
        HttpResponse<byte[]> r = postRaw("--b\r\nContent-Disposition: form-data; name=\"wsdl\"\r\n\r\n<x"
                .getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=b");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(problem(r).get("title").asText()).isEqualTo("Upload rejected");
    }

    @Test
    void unknownPathIsProblemJson404() throws Exception {
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url("/api/nope"))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(404);
    }

    /**
     * WSDL names are unbounded; response headers must stay within Tomcat's buffer.
     */
    @Test
    void hugeServiceNameStillGivesShortHeaders() throws Exception {
        String name = "S".repeat(20_000);
        byte[] wsdl = new String(wsdlWithOperations(1, TREE, "tns:Node"), StandardCharsets.UTF_8)
                .replace("name=\"Huge\"", "name=\"" + name + "\"").getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> r = postRaw(wsdl, "text/xml");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-Generator-Service")).hasValueSatisfying(v -> assertThat(v).hasSize(200));
        assertThat(r.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(v -> assertThat(v.length()).isLessThan(150));
    }

    @Test
    void securityHeadersOnTheUiAndTheApi() throws Exception {
        for (String path : new String[] { "/", "/api/health", "/api/sample-wsdl" }) {
            HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url(path))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(r.headers().firstValue("Content-Security-Policy")).as(path)
                    .hasValue(SecurityHeadersFilter.CSP);
            assertThat(r.headers().firstValue("X-Content-Type-Options")).as(path).hasValue("nosniff");
            assertThat(r.headers().firstValue("X-Frame-Options")).as(path).hasValue("DENY");
        }
    }
}
