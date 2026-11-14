package dev.soapnecromancer.generators;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.soapnecromancer.core.ParseResult;
import dev.soapnecromancer.core.WsdlParser;
import dev.soapnecromancer.mockbank.BankLedger;
import dev.soapnecromancer.runtime.model.SoapVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dev.soapnecromancer.generators.GeneratedProjectIT.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generators Requirements 2.4 and 3.3 end to end: the WSDL that mock-bank
 * serves at {@code /mock-bank/ws12?wsdl} (SOAP 1.2 binding) yields a generated
 * proxy whose own generated tests pass against a SOAP 1.2 stub, and which talks
 * SOAP 1.2 to mock-bank. The generated MCP server only speaks REST to the
 * generated proxy, so {@link GeneratedProjectIT} already covers it.
 */
class GeneratedProject12IT {
    private static final Path WORK = Path.of("target", "it", "bank12").toAbsolutePath();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static ConfigurableApplicationContext mockBank;
    private static Process proxy;
    private static String proxyUrl;

    @BeforeAll
    static void generateBuildAndStart() throws Exception {
        deleteRecursively(WORK);
        mockBank = new SpringApplicationBuilder(MockBankApp.class).properties("server.port=0").run();
        String bank = "http://127.0.0.1:" + ((WebServerApplicationContext) mockBank).getWebServer().getPort();

        HttpResponse<byte[]> wsdl = HTTP.send(HttpRequest.newBuilder(URI.create(bank + "/mock-bank/ws12?wsdl"))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(wsdl.statusCode()).isEqualTo(200);
        ParseResult parsed = new WsdlParser().parse(wsdl.body());
        assertThat(parsed.model().soapVersion()).isEqualTo(SoapVersion.SOAP_12);
        assertThat(parsed.warnings()).isEmpty();

        for (Map.Entry<String, byte[]> e : new Generator().generate(parsed).files().entrySet()) {
            Path p = WORK.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }

        Path proxyDir = WORK.resolve("proxy");
        run(proxyDir, WORK.resolve("proxy-build.log"), Duration.ofMinutes(10),
                maven("-B", "package", "-Djqwik.tries=100"));
        Path jar = proxyDir.resolve("target").resolve(ProxyGenerator.artifactId(parsed.model()) + "-1.0.0.jar");
        assertThat(jar).exists();

        int port = freePort();
        proxyUrl = "http://127.0.0.1:" + port;
        ProcessBuilder pb = new ProcessBuilder(javaBin(), "-Xmx256m", "-jar", jar.toString())
                .redirectErrorStream(true)
                .redirectOutput(WORK.resolve("proxy-run.log").toFile());
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("SOAP_ENDPOINT", bank + "/mock-bank/ws12");
        proxy = pb.start();
        awaitHealthy(proxy, proxyUrl, WORK.resolve("proxy-run.log"), Duration.ofSeconds(90));
    }

    @AfterAll
    static void stop() throws InterruptedException {
        if (proxy != null) {
            proxy.destroy();
            if (!proxy.waitFor(15, TimeUnit.SECONDS))
                proxy.destroyForcibly();
        }
        if (mockBank != null)
            mockBank.close();
    }

    @BeforeEach
    void resetLedger() {
        mockBank.getBean(BankLedger.class).reset();
    }

    /** The contract tests asserted SOAP 1.2 envelopes and media types against the 1.2 stub. */
    @Test
    void generatedContractTestsRanAndPassedAgainstTheSoap12Stub() throws Exception {
        Path reports = WORK.resolve("proxy/target/surefire-reports");
        for (String suite : new String[] { "GetBalanceContractTest", "GetStatementContractTest",
                "TransferContractTest", "RoundTripProperties" }) {
            Path xml = reports.resolve("TEST-generated.legacybankservice." + suite + ".xml");
            assertThat(xml).as("surefire report for %s", suite).exists();
            String head = Files.readString(xml, StandardCharsets.UTF_8);
            head = head.substring(0, head.indexOf('>', head.indexOf("<testsuite")));
            assertThat(attr(head, "tests")).as("%s tests", suite).isPositive();
            assertThat(attr(head, "failures") + attr(head, "errors")).as("%s failures+errors", suite).isZero();
        }
    }

    @Test
    void getBalanceOverSoap12() throws Exception {
        HttpResponse<String> r = post("/GetBalance", "{\"accountId\":\"ACC-1001\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"balance\":2500.00").contains("\"currency\":\"USD\"");
    }

    @Test
    void transferOverSoap12ReachesTheLedger() throws Exception {
        HttpResponse<String> r = post("/Transfer",
                "{\"fromAccountId\":\"ACC-1002\",\"toAccountId\":\"ACC-2001\",\"amount\":1234567890.123456789}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"newBalance\":-1234567740.123456789");
        assertThat(mockBank.getBean(BankLedger.class).account("ACC-2001").balance())
                .isEqualTo(new BigDecimal("1234567890.123456789"));
    }

    /** A SOAP 1.2 Sender fault keeps the Problem shape of a SOAP 1.1 Client fault. */
    @Test
    void senderFaultIs400WithFaultFields() throws Exception {
        HttpResponse<String> r = post("/Transfer",
                "{\"fromAccountId\":\"ACC-2001\",\"toAccountId\":\"ACC-1001\",\"amount\":5}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        JsonNode p = JSON.readTree(r.body());
        assertThat(p.get("faultcode").asText()).isEqualTo("soap:Sender");
        assertThat(p.get("faultstring").asText()).contains("Insufficient funds");
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(proxyUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
