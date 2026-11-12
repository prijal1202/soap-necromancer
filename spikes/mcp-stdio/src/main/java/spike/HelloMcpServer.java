package spike;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** Spike: a single "hello" tool served over stdio. stdout is reserved for JSON-RPC. */
public class HelloMcpServer {
    public static void main(String[] args) {
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of("type", "string")),
                "required", List.of("name"));

        var hello = SyncToolSpecification.builder()
                .tool(Tool.builder("hello", schema).description("Says hello").build())
                .callHandler((exchange, request) -> CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("Hello, " + request.arguments().get("name"))))
                        .build())
                .build();

        McpServer.sync(transport)
                .serverInfo("spike-hello", "0.0.1")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(hello)
                .build();
    }
}
