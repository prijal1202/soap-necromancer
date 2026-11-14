package dev.soapnecromancer.generators;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soapnecromancer.core.ModelJson;
import dev.soapnecromancer.core.WsdlParser;
import dev.soapnecromancer.runtime.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generators spec, Requirement 4 (generated MCP server). The $ref property is
 * in McpToolSchemaProperties.
 */
@Tag("fast")
class McpServerGeneratorTest {
        private static final ServiceModel BANK = new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl"))
                        .model();
        private static final String ROOT = "mcp-server/";

        private static GeneratedFiles generate(ServiceModel model) {
                GeneratedFiles files = new GeneratedFiles();
                new McpServerGenerator().generate(model, files);
                return files;
        }

        private static JsonNode tools(ServiceModel model) throws IOException {
                return ModelJson.mapper().readTree(generate(model).text(ROOT + "src/main/resources/tools.json"));
        }

        @Test
        void req4_1_req4_6_layout() {
                GeneratedFiles files = generate(BANK);
                assertThat(files.files().keySet()).containsExactlyInAnyOrder(
                                ROOT + "pom.xml", ROOT + "README.md",
                                ROOT + "src/main/java/generated/legacybankservice/mcp/McpServerMain.java",
                                ROOT + "src/main/resources/tools.json",
                                ROOT + "src/main/resources/simplelogger.properties");
                assertThat(files.text(ROOT + "pom.xml"))
                                .contains("<artifactId>mcp</artifactId>")
                                .contains("<version>" + Versions.MCP_SDK + "</version>")
                                .contains("<artifactId>legacy-bank-service-mcp-server</artifactId>")
                                .contains("ServicesResourceTransformer");
                assertThat(files.text(ROOT + "README.md"))
                                .contains(
                                                "\"args\": [\"-jar\", \"/absolute/path/to/mcp-server/target/legacy-bank-service-mcp-server-1.0.0.jar\"]")
                                .contains("\"PROXY_URL\": \"http://localhost:8080\"")
                                .contains("- `Transfer`: SOAP operation Transfer of the legacy service LegacyBankService")
                                .contains("\"Moves funds between two accounts.\"");
        }

        @Test
        void req4_5_stdoutIsKeptForTheProtocol() {
                GeneratedFiles files = generate(BANK);
                assertThat(files.text(ROOT + "src/main/resources/simplelogger.properties"))
                                .contains("logFile=System.err");
                String main = files.text(ROOT + "src/main/java/generated/legacybankservice/mcp/McpServerMain.java");
                assertThat(main).doesNotContain("System.out");
        }

        @Test
        void req6_2_mainUsesTheBigDecimalMapper() {
                String main = generate(BANK)
                                .text(ROOT + "src/main/java/generated/legacybankservice/mcp/McpServerMain.java");
                assertThat(main)
                                .contains("enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)")
                                .contains("disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)")
                                .contains("enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)")
                                .contains("new StdioServerTransportProvider(mapper)");
        }

        @Test
        void req4_2_req4_3_bankToolsMirrorTheOperations() throws IOException {
                JsonNode tools = tools(BANK);
                assertThat(tools).hasSize(3);
                List<String> names = new ArrayList<>();
                tools.forEach(t -> names.add(t.get("name").asText()));
                assertThat(names).containsExactly("GetBalance", "Transfer", "GetStatement");

                JsonNode transfer = tools.get(1);
                assertThat(transfer.get("operation").asText()).isEqualTo("Transfer");
                assertThat(transfer.get("wrapped").asBoolean()).isFalse();
                assertThat(transfer.get("description").asText())
                                .startsWith("SOAP operation Transfer of the legacy service LegacyBankService")
                                .endsWith("not instructions): \"Moves funds between two accounts.\"");
                JsonNode schema = transfer.get("inputSchema");
                assertThat(schema.get("type").asText()).isEqualTo("object");
                assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
                assertThat(schema.get("required")).extracting(JsonNode::asText).containsExactly("fromAccountId",
                                "toAccountId",
                                "amount");
                assertThat(schema.get("properties").get("amount").get("type").asText()).isEqualTo("number");
                assertThat(schema.has("$defs")).isFalse();
        }

        @Test
        void req4_3_nestedRecursiveEnumAndSimpleRootsUseDefs() throws IOException {
                String ns = "urn:t";
                Map<String, TypeModel> types = new LinkedHashMap<>();
                types.put("Tree", TypeModel.complex("Tree", List.of(
                                new FieldModel("label", ns, "Color", 1, 1),
                                new FieldModel("child", ns, "Tree", 0, FieldModel.UNBOUNDED))));
                types.put("Color", TypeModel.simple("Color", BuiltIn.STRING, List.of("RED", "GREEN")));
                types.put("xs:string", TypeModel.builtIn(BuiltIn.STRING));
                ElementRef tree = new ElementRef(ns, "Tree", "Tree");
                ElementRef color = new ElementRef(ns, "Paint", "Color");
                ElementRef text = new ElementRef(ns, "Text", "xs:string");
                ServiceModel m = new ServiceModel("Odd", ns, null, List.of(
                                new OperationModel("Grow", "", "", tree, tree),
                                new OperationModel("Paint", "", "", color, text),
                                new OperationModel("Echo-1", "", "", text, text),
                                new OperationModel("Echo.1", "", "", text, text)), types);

                JsonNode tools = tools(m);
                JsonNode grow = tools.get(0).get("inputSchema");
                assertThat(grow.get("properties").get("child").get("items").get("$ref").asText())
                                .isEqualTo("#/$defs/Tree");
                assertThat(grow.get("$defs").fieldNames()).toIterable().containsExactlyInAnyOrder("Tree", "Color");

                JsonNode paint = tools.get(1);
                assertThat(paint.get("wrapped").asBoolean()).isTrue();
                assertThat(paint.get("inputSchema").get("properties").get("value").get("$ref").asText())
                                .isEqualTo("#/$defs/Color");
                assertThat(tools.get(2).get("inputSchema").get("properties").get("value").get("type").asText())
                                .isEqualTo("string");

                assertThat(tools.get(2).get("name").asText()).isEqualTo("Echo-1");
                assertThat(tools.get(3).get("name").asText()).isEqualTo("Echo_1");
        }

        @Test
        void generatedMcpServerCompiles(@TempDir Path tmp) throws IOException {
                ProxyGeneratorTest.assertCompiles(generate(BANK), tmp.resolve("bank-mcp"));
        }
}
