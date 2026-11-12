package spike;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Spike (Task 9, generators spec task 10): with a BigDecimal-preserving Jackson 3 mapper, do tool
 * arguments arrive as BigDecimal with their exact digits and scale? Echoes class + re-serialized text.
 */
public class DecimalMcpServer {
    public static void main(String[] args) {
        JsonMapper jackson = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
        // "default" argument: the SDK's out-of-the-box mapper, to see what it does to decimals.
        io.modelcontextprotocol.json.McpJsonMapper mapper = args.length > 0 && args[0].equals("default")
                ? io.modelcontextprotocol.json.McpJsonDefaults.getMapper()
                : new JacksonMcpJsonMapper(jackson);
        var transport = new StdioServerTransportProvider(mapper);

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("amount", Map.of("type", "number")),
                "required", List.of("amount"));

        var echo = SyncToolSpecification.builder()
                .tool(Tool.builder("echo", schema).description("Echoes argument types").build())
                .callHandler((exchange, request) -> {
                    Object amount = request.arguments().get("amount");
                    String forwarded;
                    try {
                        forwarded = jackson.writeValueAsString(request.arguments());
                    } catch (Exception e) {
                        forwarded = "ERR " + e;
                    }
                    String text = (amount == null ? "null" : amount.getClass().getName()) + " " + amount + " " + forwarded;
                    return CallToolResult.builder().content(List.of(new McpSchema.TextContent(text))).build();
                })
                .build();

        McpServer.sync(transport)
                .jsonMapper(mapper)
                .serverInfo("spike-decimal", "0.0.1")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(echo)
                .build();
    }
}
