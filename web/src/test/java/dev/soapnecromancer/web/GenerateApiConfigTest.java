package dev.soapnecromancer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration-dependent behaviour: output cap, busy 503 with Retry-After, a
 * configured public base URL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "necromancer.max-output-bytes=50000",
        "necromancer.max-concurrent=1",
        "necromancer.queue-wait=300ms",
        "mockbank.public-base-url=https://necro.example.com" })
class GenerateApiConfigTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    GenerationSlots slots;

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> generate(String wsdl) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/generate"))
                .header("Content-Type", "text/xml").POST(HttpRequest.BodyPublishers.ofString(wsdl)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void outputOverTheCapIs422WithLimitOutput() throws Exception {
        HttpResponse<String> r = generate(get("/api/sample-wsdl").body());
        assertThat(r.statusCode()).isEqualTo(422);
        JsonNode p = json.readTree(r.body());
        assertThat(p.get("warnings").get(0).get("code").asText()).isEqualTo("LIMIT_OUTPUT");
    }

    @Test
    void busyGeneratorAnswers503WithRetryAfterAfterTheQueueWait() throws Exception {
        String wsdl = get("/api/sample-wsdl").body();
        slots.drainForTest();
        try {
            long start = System.nanoTime();
            HttpResponse<String> r = generate(wsdl);
            long waitedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.headers().firstValue("Retry-After"))
                    .hasValue(String.valueOf(GenerationSlots.RETRY_AFTER_SECONDS));
            assertThat(r.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
            assertThat(json.readTree(r.body()).get("detail").asText()).contains("busy");
            assertThat(waitedMs).as("waited for a slot before giving up").isGreaterThanOrEqualTo(250);
        } finally {
            slots.refillForTest(1);
        }
    }

    /**
     * A 5,000-type chain fits the default 2 MB upload cap and must be a 422, not a
     * StackOverflowError/500.
     */
    @Test
    void longTypeChainIs422NotAStackOverflow() throws Exception {
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            types.append("<xs:complexType name=\"T").append(i)
                    .append("\"><xs:sequence><xs:element name=\"n\" type=\"tns:T")
                    .append(i + 1).append("\" minOccurs=\"0\"/></xs:sequence></xs:complexType>");
        }
        types.append("<xs:complexType name=\"T5000\"><xs:sequence/></xs:complexType>");
        byte[] wsdl = GenerateApiTest.wsdlWithOperations(1, types.toString(), "tns:T0");
        assertThat(wsdl.length).isBetween(65_536, 2 * 1024 * 1024);
        HttpResponse<String> r = generate(new String(wsdl, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(json.readTree(r.body()).get("warnings").toString()).contains("LIMIT_DEPTH");
    }

    @Test
    void configuredPublicBaseUrlIsUsedForTheSampleWsdl() throws Exception {
        assertThat(get("/api/sample-wsdl").body()).contains("location=\"https://necro.example.com/mock-bank/ws\"");
    }

    @Test
    void mockBankResetIsReachableInTheWebApp() throws Exception {
        HttpResponse<String> r = HTTP
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock-bank/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("ACC-1001");
    }
}
