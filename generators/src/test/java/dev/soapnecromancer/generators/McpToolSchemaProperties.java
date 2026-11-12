package dev.soapnecromancer.generators;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soapnecromancer.core.ModelJson;
import dev.soapnecromancer.runtime.ModelArbitraries;
import net.jqwik.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Generators Requirement 4.3: for any model in the supported subset, every tool schema is self-contained. */
@Tag("fast")
class McpToolSchemaProperties {

    @Property(tries = 200)
    void everyRefResolvesInsideItsToolSchema(@ForAll("models") ModelArbitraries.Generated g) throws IOException {
        GeneratedFiles files = new GeneratedFiles();
        new McpServerGenerator().generate(g.model(), files);
        JsonNode tools = ModelJson.mapper().readTree(files.text("mcp-server/src/main/resources/tools.json"));
        for (JsonNode tool : tools) {
            JsonNode schema = tool.get("inputSchema");
            assertThat(schema.get("type").asText()).isEqualTo("object");
            Set<String> defs = new HashSet<>();
            if (schema.has("$defs")) schema.get("$defs").fieldNames().forEachRemaining(defs::add);
            List<String> refs = new ArrayList<>();
            collectRefs(schema, refs);
            for (String ref : refs) {
                assertThat(ref).startsWith("#/$defs/");
                assertThat(defs).as("defs for %s", ref).contains(ref.substring("#/$defs/".length()));
            }
        }
    }

    @Provide
    Arbitrary<ModelArbitraries.Generated> models() {
        return ModelArbitraries.models();
    }

    private static void collectRefs(JsonNode n, List<String> out) {
        if (n.isObject()) {
            if (n.has("$ref")) out.add(n.get("$ref").asText());
            n.fields().forEachRemaining(e -> collectRefs(e.getValue(), out));
        } else if (n.isArray()) {
            n.forEach(c -> collectRefs(c, out));
        }
    }
}
