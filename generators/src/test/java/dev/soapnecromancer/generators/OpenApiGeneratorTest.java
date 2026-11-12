package dev.soapnecromancer.generators;

import dev.soapnecromancer.core.WsdlParser;
import dev.soapnecromancer.runtime.model.*;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Generators spec, Requirement 1. */
@Tag("fast")
class OpenApiGeneratorTest {
    private static final ServiceModel BANK = new WsdlParser().parse(resource("bank.wsdl")).model();
    private final OpenApiGenerator generator = new OpenApiGenerator();

    static byte[] resource(String name) {
        try (InputStream in = OpenApiGeneratorTest.class.getResourceAsStream("/" + name)) {
            if (in == null)
                throw new IllegalArgumentException("missing test resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OpenAPI parseValid(String yaml) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult r = new OpenAPIV3Parser().readContents(yaml, null, options);
        assertThat(r.getMessages()).as("swagger-parser messages").isEmpty();
        return r.getOpenAPI();
    }

    @Test
    void req1_10_bankMatchesGolden() throws IOException {
        String actual = generator.generate(BANK);
        // Written on every run so a reviewed diff can be copied over the golden file.
        Path out = Path.of("target", "bank.openapi.actual.yaml");
        Files.createDirectories(out.getParent());
        Files.writeString(out, actual, StandardCharsets.UTF_8);
        String golden = new String(resource("bank.openapi.yaml"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual).as("compare with %s", out.toAbsolutePath()).isEqualTo(golden);
    }

    @Test
    void req1_9_bankIsValidOpenApi303AndDeterministic() {
        String yaml = generator.generate(BANK);
        assertThat(generator.generate(BANK)).isEqualTo(yaml);
        OpenAPI api = parseValid(yaml);
        assertThat(api.getOpenapi()).isEqualTo("3.0.3");
        assertThat(api.getServers().get(0).getUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void req1_2_1_3_onePostPathPerOperationWithProblemResponses() {
        OpenAPI api = parseValid(generator.generate(BANK));
        assertThat(api.getPaths()).containsOnlyKeys("/GetBalance", "/Transfer", "/GetStatement");
        var post = api.getPaths().get("/Transfer").getPost();
        assertThat(post.getOperationId()).isEqualTo("Transfer");
        assertThat(post.getSummary()).isEqualTo("Moves funds between two accounts.");
        assertThat(post.getRequestBody().getRequired()).isTrue();
        assertThat(post.getRequestBody().getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/TransferRequest");
        assertThat(post.getResponses()).containsOnlyKeys("200", "400", "502", "504");
        assertThat(post.getResponses().get("504").getContent().get("application/problem+json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/Problem");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void req1_4_to_1_6_schemaMapping() {
        Map<String, Schema> s = parseValid(generator.generate(BANK)).getComponents().getSchemas();
        Schema transfer = s.get("TransferRequest");
        // swagger-parser re-sorts "required"; model order in the YAML itself is checked
        // by the golden file.
        assertThat(transfer.getRequired()).containsExactlyInAnyOrder("fromAccountId", "toAccountId", "amount");
        assertThat(transfer.getProperties()).containsOnlyKeys("fromAccountId", "toAccountId", "amount", "memo");
        assertThat(transfer.getAdditionalProperties()).isEqualTo(false);
        Schema amount = (Schema) transfer.getProperties().get("amount");
        assertThat(amount.getType()).isEqualTo("number");
        assertThat(amount.getFormat()).isEqualTo("decimal");

        Schema statement = s.get("GetStatementResponse");
        Schema line = (Schema) statement.getProperties().get("line");
        assertThat(line.getType()).isEqualTo("array");
        assertThat(line.getItems().get$ref()).isEqualTo("#/components/schemas/StatementLine");
        assertThat(line.getMinItems()).isNull();
        assertThat(line.getMaxItems()).isNull();
        assertThat(statement.getRequired()).containsExactly("accountId");

        assertThat(s.get("TransactionType").getEnum()).containsExactly("DEBIT", "CREDIT");
        Schema maxLines = (Schema) s.get("GetStatementRequest").getProperties().get("maxLines");
        assertThat(maxLines.getFormat()).isEqualTo("int32");
        Schema asOf = (Schema) s.get("GetBalanceResponse").getProperties().get("asOf");
        assertThat(asOf.getFormat()).isEqualTo("date-time");
        assertThat(s).doesNotContainKeys("xs:string", "xs_string");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void req1_5_1_7_boundedArraysOddNamesAndProblemCollision() {
        String ns = "urn:t";
        ElementRef in = new ElementRef(ns, "Problem", "Problem");
        ElementRef out = new ElementRef(ns, "Out", "weird name:1");
        Map<String, TypeModel> types = new LinkedHashMap<>();
        types.put("Problem", TypeModel.complex("Problem", List.of(new FieldModel("tags", ns, "xs:string", 2, 5))));
        types.put("weird name:1",
                TypeModel.complex("weird name:1", List.of(new FieldModel("yes", ns, "xs:long", 0, 1))));
        types.put("xs:string", TypeModel.builtIn(BuiltIn.STRING));
        types.put("xs:long", TypeModel.builtIn(BuiltIn.LONG));
        ServiceModel m = new ServiceModel("Odd Service", ns, null,
                List.of(new OperationModel("Op", "", "", in, out)), types);

        Map<String, Schema> s = parseValid(generator.generate(m)).getComponents().getSchemas();
        assertThat(s).containsOnlyKeys("Problem", "Problem_2", "weird_name_1");
        Schema tags = (Schema) s.get("Problem_2").getProperties().get("tags");
        assertThat(tags.getMinItems()).isEqualTo(2);
        assertThat(tags.getMaxItems()).isEqualTo(5);
        assertThat(s.get("Problem").getProperties()).containsKey("faultstring");
        assertThat(((Schema) s.get("weird_name_1").getProperties().get("yes")).getFormat()).isEqualTo("int64");
    }

    @Test
    void documentationWithQuotesAndNewlinesStaysValid() {
        ElementRef ref = new ElementRef("urn:t", "R", "R");
        ServiceModel m = new ServiceModel("S", "urn:t", null,
                List.of(new OperationModel("Op", "urn:\"x\"", "Says \"hi\": \\ #not-a-comment", ref, ref)),
                Map.of("R", TypeModel.complex("R", List.of())));
        OpenAPI api = parseValid(generator.generate(m));
        assertThat(api.getPaths().get("/Op").getPost().getSummary()).isEqualTo("Says \"hi\": \\ #not-a-comment");
    }

    @Test
    void decimalSchemaCanBeStringForMcp() {
        JsonSchemas js = new JsonSchemas(BANK, List.of(), "#/$defs/", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) ((Map<?, ?>) js.definition("TransferRequest")
                .get("properties")).get("amount");
        assertThat(amount).containsEntry("type", "string").containsKey("pattern");
        assertThat("1234567890.123456789").matches((String) amount.get("pattern"));
        assertThat(new BigDecimal("-0.10").toPlainString()).matches((String) amount.get("pattern"));
    }
}
