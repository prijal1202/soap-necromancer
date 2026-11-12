package dev.soapnecromancer.generators;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.soapnecromancer.core.ParseResult;
import dev.soapnecromancer.core.WsdlParser;
import dev.soapnecromancer.runtime.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generators spec, Requirement 2 (generated proxy), checked without running
 * Maven.
 */
@Tag("fast")
class ProxyGeneratorTest {
        private static final ServiceModel BANK = new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl"))
                        .model();
        private static final String PKG_DIR = "proxy/src/main/java/generated/legacybankservice/";

        private static GeneratedFiles generate(ServiceModel model) {
                GeneratedFiles files = new GeneratedFiles();
                new ProxyGenerator().generate(model, files);
                return files;
        }

        @Test
        void req2_1_req2_3_bankProxyHasOneControllerPerOperationAndNothingElse() {
                GeneratedFiles files = generate(BANK);
                List<String> generatedJava = files.files().keySet().stream()
                                .filter(p -> p.startsWith(PKG_DIR)).sorted().toList();
                assertThat(generatedJava).containsExactly(
                                PKG_DIR + "GetBalanceController.java", PKG_DIR + "GetStatementController.java",
                                PKG_DIR + "HealthController.java", PKG_DIR + "ModelConfig.java",
                                PKG_DIR + "ProblemHandler.java",
                                PKG_DIR + "ProxyApplication.java", PKG_DIR + "SoapInvoker.java",
                                PKG_DIR + "SoapProperties.java",
                                PKG_DIR + "TransferController.java");
                assertThat(files.text(PKG_DIR + "TransferController.java"))
                                .contains("@PostMapping(path = \"/Transfer\"")
                                .contains("invoker.invoke(\"Transfer\", body)")
                                .contains("Moves funds between two accounts.");
                assertThat(files.text("proxy/pom.xml"))
                                .contains("<artifactId>legacy-bank-service-proxy</artifactId>")
                                .contains("<version>" + Versions.SPRING_BOOT + "</version>");
        }

        @Test
        void req2_2_runtimeSourcesAreCopiedVerbatimAndNoModelClassesAreGenerated() throws IOException {
                GeneratedFiles files = generate(BANK);
                Path runtimeMain = Path.of("..", "runtime", "src", "main", "java");
                List<String> copied = new ArrayList<>();
                try (var walk = Files.walk(runtimeMain)) {
                        for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                                String rel = runtimeMain.relativize(p).toString().replace('\\', '/');
                                String generatedPath = "proxy/src/main/java/" + rel;
                                copied.add(generatedPath);
                                assertThat(files.text(generatedPath)).as(rel)
                                                .isEqualTo(Files.readString(p, StandardCharsets.UTF_8).replace("\r\n",
                                                                "\n"));
                        }
                }
                assertThat(copied).contains("proxy/src/main/java/dev/soapnecromancer/runtime/model/ServiceModel.java",
                                "proxy/src/main/java/dev/soapnecromancer/runtime/SoapClient.java");
                assertThat(files.files().keySet())
                                .contains("proxy/src/test/java/dev/soapnecromancer/runtime/ModelArbitraries.java");

                // Only runtime sources, the fixed classes and controllers: no per-type
                // model/DTO classes.
                List<String> otherJava = files.files().keySet().stream()
                                .filter(p -> p.endsWith(".java") && !p.startsWith(PKG_DIR)
                                                && !p.contains("/dev/soapnecromancer/runtime/"))
                                .toList();
                assertThat(otherJava).isEmpty();
                assertThat(files.files().keySet())
                                .noneMatch(p -> p.contains("GetBalanceRequest") || p.contains("StatementLine"));
        }

        @Test
        void req2_2_modelJsonLoadsBackIntoTheRuntimeRecords() throws IOException {
                String json = generate(BANK).text("proxy/src/main/resources/model.json");
                ServiceModel back = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                .build()
                                .readValue(json, ServiceModel.class);
                assertThat(back).isEqualTo(BANK);
        }

        @Test
        void req2_5_req6_1_endpointDefaultAndDecimalFlags() {
                String props = generate(BANK).text("proxy/src/main/resources/application.properties");
                assertThat(props)
                                .contains("soap.endpoint=${SOAP_ENDPOINT:http://localhost:8080/mock-bank/ws}")
                                .contains("server.port=${PORT:8080}")
                                .contains("spring.jackson.deserialization.use-big-decimal-for-floats=true")
                                .contains("spring.jackson.generator.write-bigdecimal-as-plain=true");
        }

        /**
         * A hostile WSDL must not be able to inject code through names or
         * documentation.
         */
        static ServiceModel hostile() {
                ElementRef ref = new ElementRef("urn:t", "R", "R");
                String doc = "ends comment */ class Evil {} /* and \\u000a unicode \\u002a/ escapes \"quotes\"";
                return new ServiceModel("Weird Service!", "urn:t", "http://legacy.example/ws?a=1}&b=\\",
                                List.of(new OperationModel("Get-Balance.v2", "urn:x", doc, ref, ref),
                                                new OperationModel("GetBalance", "", "", ref, ref),
                                                new OperationModel("getBalance", "", "", ref, ref),
                                                new OperationModel("Health", "", "", ref, ref)),
                                Map.of("R", TypeModel.complex("R", List.of())));
        }

        @Test
        void namesAreSanitizedAndUniqueCaseInsensitively() {
                List<String> names = ProxyGenerator.controllers(hostile()).stream()
                                .map(ProxyGenerator.Controller::className)
                                .toList();
                assertThat(names).containsExactly("Get_Balance_v2Controller", "GetBalanceController",
                                "GetBalanceController2",
                                "HealthController2");
                assertThat(Names.javaPackage("Weird Service!")).isEqualTo("generated.weirdservice");
                assertThat(Names.javaPackage("class")).isEqualTo("generated.class_");
                assertThat(Names.javaPackage("2fa")).isEqualTo("generated.s2fa");
                assertThat(Names.artifactId("LegacyBankService", "proxy")).isEqualTo("legacy-bank-service-proxy");
        }

        /** Generated proxy plus its generated tests (TestGenerator). */
        static GeneratedFiles generateWithTests(ServiceModel model) {
                GeneratedFiles files = generate(model);
                new TestGenerator().generate(model, files);
                return files;
        }

        @Test
        void generatedSourcesCompile(@TempDir Path tmp) throws IOException {
                assertCompiles(generateWithTests(BANK), tmp.resolve("bank"));
        }

        @Test
        void hostileNamesAndDocumentationStillCompile(@TempDir Path tmp) throws IOException {
                GeneratedFiles files = generateWithTests(hostile());
                // "}" and "\" cannot sit safely in a Spring placeholder default: no default,
                // SOAP_ENDPOINT required.
                assertThat(files.text("proxy/src/main/resources/application.properties"))
                                .contains("soap.endpoint=${SOAP_ENDPOINT:}\n");
                assertCompiles(files, tmp.resolve("hostile"));
        }

        /**
         * Compiles every generated .java file (main and test) with javac against the
         * test classpath.
         */
        static void assertCompiles(GeneratedFiles files, Path dir) throws IOException {
                List<String> sources = new ArrayList<>();
                for (Map.Entry<String, byte[]> e : files.files().entrySet()) {
                        if (!e.getKey().endsWith(".java"))
                                continue;
                        Path p = dir.resolve(e.getKey());
                        Files.createDirectories(p.getParent());
                        Files.write(p, e.getValue());
                        sources.add(p.toString());
                }
                String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                List<String> args = new ArrayList<>(
                                List.of("-proc:none", "--release", "21", "-encoding", "UTF-8", "-nowarn",
                                                "-classpath", cp, "-d", dir.resolve("classes").toString()));
                args.addAll(sources);

                JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                int rc = javac.run(null, null, err, args.toArray(String[]::new));
                assertThat(rc).as("javac output:%n%s", err.toString(StandardCharsets.UTF_8)).isZero();
        }
}
