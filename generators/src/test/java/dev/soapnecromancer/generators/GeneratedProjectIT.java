package dev.soapnecromancer.generators;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.soapnecromancer.core.WsdlParser;
import dev.soapnecromancer.mockbank.BankLedger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generators spec, Requirement 7.2: generate from bank.wsdl, build the
 * generated proxy with Maven,
 * run it against mock-bank, and call it over HTTP. Slow (it runs Maven), so
 * failsafe runs it in
 * the verify phase only. The Kiro hook runs "test" and never reaches it. Skip
 * with -DskipITs.
 */
class GeneratedProjectIT {
    private static final Path WORK = Path.of("target", "it", "bank").toAbsolutePath();
    /**
     * Exact node factory: the default one strips trailing zeros (2500.00 ->
     * 2.5E+3).
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
            .build();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @SpringBootApplication(scanBasePackages = "dev.soapnecromancer.mockbank")
    static class MockBankApp {
    }

    private static ConfigurableApplicationContext mockBank;
    private static Process proxy;
    private static String proxyUrl;

    @BeforeAll
    static void generateBuildAndStart() throws Exception {
        deleteRecursively(WORK);
        GeneratedFiles files = new Generator()
                .generate(new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl")));
        for (Map.Entry<String, byte[]> e : files.files().entrySet()) {
            Path p = WORK.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }

        mockBank = new SpringApplicationBuilder(MockBankApp.class).properties("server.port=0").run();
        int bankPort = ((WebServerApplicationContext) mockBank).getWebServer().getPort();

        Path proxyDir = WORK.resolve("proxy");
        run(proxyDir, WORK.resolve("proxy-build.log"), Duration.ofMinutes(10), maven("-B", "package"));

        Path jar = proxyDir.resolve("target").resolve(ProxyGenerator.artifactId(
                new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl")).model()) + "-1.0.0.jar");
        assertThat(jar).exists();

        int port = freePort();
        proxyUrl = "http://127.0.0.1:" + port;
        ProcessBuilder pb = new ProcessBuilder(javaBin(), "-Xmx256m", "-jar", jar.toString())
                .redirectErrorStream(true)
                .redirectOutput(WORK.resolve("proxy-run.log").toFile());
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("SOAP_ENDPOINT", "http://127.0.0.1:" + bankPort + "/mock-bank/ws");
        proxy = pb.start();
        awaitHealthy(Duration.ofSeconds(90));
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

    // ------------------------------------------------------------------ calls

    @Test
    void getBalanceReturnsJsonFromTheSoapBackend() throws Exception {
        HttpResponse<String> r = post("/GetBalance", "{\"accountId\":\"ACC-1001\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        // The wire text itself: scale kept, plain notation.
        assertThat(r.body()).contains("\"balance\":2500.00");
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("accountId").asText()).isEqualTo("ACC-1001");
        assertThat(body.get("balance").decimalValue()).isEqualTo(new BigDecimal("2500.00"));
        assertThat(body.get("overdraftAllowed").asBoolean()).isFalse();
        assertThat(body.get("asOf").asText()).isNotBlank();
    }

    /**
     * Generators Requirement 6: every digit reaches the backend and comes back, no
     * exponent.
     */
    @Test
    void transferKeepsEveryDigitEndToEnd() throws Exception {
        HttpResponse<String> r = post("/Transfer",
                "{\"fromAccountId\":\"ACC-1002\",\"toAccountId\":\"ACC-2001\",\"amount\":1234567890.123456789,\"memo\":\"Necromancy\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"newBalance\":-1234567740.123456789").doesNotContain("E+").doesNotContain("E-");
        assertThat(mockBank.getBean(BankLedger.class).account("ACC-2001").balance())
                .isEqualTo(new BigDecimal("1234567890.123456789"));

        HttpResponse<String> statement = post("/GetStatement", "{\"accountId\":\"ACC-2001\",\"maxLines\":5}");
        JsonNode lines = JSON.readTree(statement.body()).get("line");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("type").asText()).isEqualTo("CREDIT");
        assertThat(lines.get(0).get("description").asText()).isEqualTo("Necromancy");
        assertThat(lines.get(0).get("amount").decimalValue().toPlainString()).isEqualTo("1234567890.123456789");
    }

    @Test
    void emptyStatementIsAnEmptyArray() throws Exception {
        HttpResponse<String> r = post("/GetStatement", "{\"accountId\":\"ACC-2001\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("line").isArray()).isTrue();
        assertThat(JSON.readTree(r.body()).get("line")).isEmpty();
    }

    // ------------------------------------------------------------------ errors
    // (Requirement 2.6)

    @Test
    void unknownFieldIs400WithItsPath() throws Exception {
        HttpResponse<String> r = post("/GetBalance", "{\"acountId\":\"ACC-1001\"}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
        assertThat(JSON.readTree(r.body()).get("path").asText()).isEqualTo("$.acountId");
    }

    @Test
    void soapClientFaultIs400WithFaultFields() throws Exception {
        HttpResponse<String> r = post("/Transfer",
                "{\"fromAccountId\":\"ACC-2001\",\"toAccountId\":\"ACC-1001\",\"amount\":5}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        JsonNode p = JSON.readTree(r.body());
        assertThat(p.get("faultcode").asText()).isEqualTo("soap:Client");
        assertThat(p.get("faultstring").asText()).contains("Insufficient funds");
    }

    @Test
    void invalidJsonIs400() throws Exception {
        HttpResponse<String> r = post("/GetBalance", "{\"accountId\":");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(r.body()).get("path").asText()).isEqualTo("$");
    }

    // ------------------------------------------------------------------ helpers

    private static HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(proxyUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void awaitHealthy(Duration max) throws Exception {
        Instant deadline = Instant.now().plus(max);
        while (Instant.now().isBefore(deadline)) {
            if (!proxy.isAlive()) {
                throw new AssertionError("generated proxy exited with " + proxy.exitValue() + ":\n"
                        + tail(WORK.resolve("proxy-run.log")));
            }
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(proxyUrl + "/health"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200)
                    return;
            } catch (IOException notYet) {
                // still starting
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "generated proxy not healthy after " + max + ":\n" + tail(WORK.resolve("proxy-run.log")));
    }

    private static void run(Path dir, Path log, Duration timeout, List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new AssertionError(command + " timed out:\n" + tail(log));
        }
        assertThat(p.exitValue()).as("%s failed:%n%s", command, tail(log)).isZero();
    }

    private static List<String> maven(String... args) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>(windows ? List.of("cmd", "/c", "mvn") : List.of("mvn"));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
        } catch (IOException e) {
            return "(no log: " + e.getMessage() + ")";
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
                Files.delete(p);
        }
    }
}
