package dev.soapnecromancer.generators;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soapnecromancer.core.ModelJson;
import dev.soapnecromancer.core.ParseResult;
import dev.soapnecromancer.core.Warning;
import dev.soapnecromancer.core.WsdlParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generators spec, Requirement 5 (packaging). */
@Tag("fast")
class GeneratorTest {
    private static final ParseResult BANK = new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl"));

    @Test
    void req5_1_layout() {
        GeneratedFiles files = new Generator().generate(BANK);
        assertThat(files.files().keySet()).contains("model.json", "openapi.yaml", "generator-report.json", "README.md",
                "proxy/pom.xml", "proxy/src/main/resources/model.json");
        assertThat(files.files().keySet()).allMatch(p -> p.equals("model.json") || p.equals("openapi.yaml")
                || p.equals("generator-report.json") || p.equals("README.md") || p.startsWith("proxy/")
                || p.startsWith("mcp-server/"));
        assertThat(files.files().keySet()).contains("mcp-server/pom.xml", "mcp-server/src/main/resources/tools.json");
    }

    @Test
    void req5_2_req5_5_reportAndReadmeListWarnings() throws IOException {
        List<Warning> warnings = List.of(new Warning("XSD_CHOICE", "xs:choice is not supported", "type[A]/choice"),
                new Warning("WS_SECURITY", "ignored `policy`", "definitions/Policy"));
        GeneratedFiles files = new Generator().generate(new ParseResult(BANK.model(), warnings));

        JsonNode report = ModelJson.mapper().readTree(files.text("generator-report.json"));
        assertThat(report.get("version").asText()).isEqualTo(Versions.GENERATOR);
        assertThat(report.get("warnings")).hasSize(2);
        assertThat(report.get("warnings").get(0).get("code").asText()).isEqualTo("XSD_CHOICE");
        assertThat(report.get("warnings").get(0).get("location").asText()).isEqualTo("type[A]/choice");

        assertThat(files.text("README.md"))
                .contains("- `XSD_CHOICE` at `type[A]/choice`: xs:choice is not supported")
                .contains("ignored 'policy'")
                .contains("java -jar target/legacy-bank-service-proxy-1.0.0.jar")
                .contains("- `POST /Transfer`: Moves funds between two accounts.");
        assertThat(new Generator().generate(BANK).text("README.md"))
                .contains("None. Every feature in the WSDL is supported.");
    }

    @Test
    void req5_3_zipIsDeterministicSortedAndComplete() throws IOException {
        byte[] a = new Generator().generate(BANK).zip();
        byte[] b = new Generator().generate(BANK).zip();
        assertThat(a).isEqualTo(b);

        GeneratedFiles files = new Generator().generate(BANK);
        List<String> names = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(a))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null;) {
                names.add(e.getName());
                assertThat(e.getTimeLocal()).isEqualTo(GeneratedFiles.ZIP_TIME);
                assertThat(zin.readAllBytes()).as(e.getName()).isEqualTo(files.files().get(e.getName()));
            }
        }
        assertThat(names).isSorted().containsExactlyInAnyOrderElementsOf(files.files().keySet());
    }

    @Test
    void unsafePathsAreRejected() {
        GeneratedFiles files = new GeneratedFiles();
        for (String bad : List.of("../evil", "/abs", "a/../../b", "a\\b", "a//b", "./x", "")) {
            assertThatThrownBy(() -> files.add(bad, "x")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        files.add("ok/file.txt", "x");
        assertThatThrownBy(() -> files.add("ok/file.txt", "y")).isInstanceOf(IllegalStateException.class);
    }
}
