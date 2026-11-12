package dev.soapnecromancer.generators;

import dev.soapnecromancer.core.ParseResult;
import dev.soapnecromancer.core.WsdlParser;
import dev.soapnecromancer.runtime.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generators Requirement 5.6: output that would be too large is refused, never built. */
@Tag("fast")
class GenerationLimitsTest {
    private static final ParseResult BANK = new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl"));

    /** n operations whose inputs all reach the same m named types: tool schemas need n x m definitions. */
    static ServiceModel wide(int operations, int sharedTypes) {
        String ns = "urn:wide";
        Map<String, TypeModel> types = new LinkedHashMap<>();
        List<FieldModel> rootFields = new ArrayList<>();
        for (int i = 0; i < sharedTypes; i++) {
            types.put("S" + i, TypeModel.complex("S" + i, List.of(new FieldModel("v", ns, "xs:string", 1, 1))));
            rootFields.add(new FieldModel("f" + i, ns, "S" + i, 0, 1));
        }
        types.put("Root", TypeModel.complex("Root", rootFields));
        types.put("xs:string", TypeModel.builtIn(BuiltIn.STRING));
        ElementRef ref = new ElementRef(ns, "Root", "Root");
        List<OperationModel> ops = new ArrayList<>();
        for (int i = 0; i < operations; i++) ops.add(new OperationModel("Op" + i, "", "", ref, ref));
        return new ServiceModel("Wide", ns, null, ops, types);
    }

    @Test
    void bankFitsComfortably() {
        GeneratedFiles files = new Generator().generate(BANK);
        assertThat(files.totalBytes()).isLessThan(Generator.DEFAULT_MAX_OUTPUT_BYTES / 10);
    }

    @Test
    void quadraticToolSchemasAreRefusedBeforeBuilding() {
        // 200 x 1000 = 200,000 definitions: within the parser's limits, far over the schema limit.
        ServiceModel wide = wide(200, 1000);
        long start = System.nanoTime();
        assertThatThrownBy(() -> new Generator().generate(new ParseResult(wide, List.of())))
                .isInstanceOfSatisfying(GenerationLimitException.class,
                        e -> assertThat(e.code()).isEqualTo("LIMIT_TOOL_SCHEMAS"));
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms to refuse").isLessThan(2_000);
    }

    @Test
    void outputCapIsEnforcedWhileWriting() {
        assertThatThrownBy(() -> new Generator(50_000, Generator.DEFAULT_MAX_TOOL_DEFINITIONS).generate(BANK))
                .isInstanceOfSatisfying(GenerationLimitException.class,
                        e -> assertThat(e.code()).isEqualTo("LIMIT_OUTPUT"));
    }

    @Test
    void recursiveTreeGeneratesAndCompiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        String ns = "urn:tree";
        Map<String, TypeModel> types = new LinkedHashMap<>();
        types.put("Node", TypeModel.complex("Node", List.of(
                new FieldModel("label", ns, "xs:string", 1, 1),
                new FieldModel("child", ns, "Node", 0, FieldModel.UNBOUNDED))));
        types.put("xs:string", TypeModel.builtIn(BuiltIn.STRING));
        ElementRef ref = new ElementRef(ns, "Tree", "Node");
        ServiceModel tree = new ServiceModel("Trees", ns, "http://legacy.example/ws",
                List.of(new OperationModel("Grow", "", "", ref, ref)), types);
        GeneratedFiles files = new Generator().generate(new ParseResult(tree, List.of()));
        assertThat(files.text("mcp-server/src/main/resources/tools.json")).contains("\"$ref\" : \"#/$defs/Node\"");
        ProxyGeneratorTest.assertCompiles(files, tmp);
    }
}
