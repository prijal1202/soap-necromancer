package dev.soapnecromancer.generators;

import dev.soapnecromancer.core.ModelJson;
import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generator: canonical model -> {@code mcp-server/**}, the generated MCP server
 * (generators spec,
 * Requirement 4). A plain Java 21 project on the MCP Java SDK, stdio transport,
 * one tool per
 * operation. Tool definitions are data ({@code tools.json}), so one small
 * template serves every WSDL.
 */
public final class McpServerGenerator {
    static final String ROOT = "mcp-server/";

    public void generate(ServiceModel model, GeneratedFiles out) {
        String pkg = Names.javaPackage(model.name()) + ".mcp";
        List<Map<String, Object>> tools = tools(model);

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("package", pkg);
        ctx.put("artifactId", artifactId(model));
        ctx.put("generatorVersion", Versions.GENERATOR);
        ctx.put("mcpSdkVersion", Versions.MCP_SDK);
        ctx.put("serviceName", Escapes.markdown(model.name()));
        ctx.put("serviceNameXmlComment", Escapes.xmlComment(model.name()));
        ctx.put("serviceNamePom", Escapes.pomText(model.name()));
        ctx.put("serviceNameDoc", Escapes.javaComment(model.name()));
        ctx.put("serverNameJava", Escapes.javaString(model.name()));
        ctx.put("mcpKey", Names.artifactId(model.name(), "soap"));
        List<Map<String, String>> readmeTools = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            readmeTools.add(Map.of("name", (String) t.get("name"), "description",
                    Escapes.markdown((String) t.get("description"))));
        }
        ctx.put("tools", readmeTools);

        out.add(ROOT + "pom.xml", Templates.render("mcp/pom.xml", ctx));
        out.add(ROOT + "README.md", Templates.render("mcp/README.md", ctx));
        out.add(ROOT + "src/main/java/" + pkg.replace('.', '/') + "/McpServerMain.java",
                Templates.render("mcp/McpServerMain.java", ctx));
        out.add(ROOT + "src/main/resources/tools.json", ModelJson.write(tools));
        out.add(ROOT + "src/main/resources/simplelogger.properties",
                Templates.render("mcp/simplelogger.properties", ctx));
    }

    public static String artifactId(ServiceModel model) {
        return Names.artifactId(model.name(), "mcp-server");
    }

    /**
     * Each tool embeds every named type it reaches under $defs, so the total grows
     * as
     * operations x reachable types (200 operations sharing 1,000 types would be
     * 200,000 definitions).
     * Counts that total cheaply and refuses before any schema map is built.
     */
    static void checkSchemaSize(ServiceModel model, int maxDefinitions) {
        JsonSchemas schemas = new JsonSchemas(model, List.of(), "#/$defs/", false);
        long total = 0;
        for (OperationModel op : model.operations()) {
            total += schemas.reachableNamedCount(op.input().typeId());
            if (total > maxDefinitions) {
                throw new GenerationLimitException("LIMIT_TOOL_SCHEMAS", "The MCP tool schemas would need more than "
                        + maxDefinitions + " type definitions (operations x shared types)");
            }
        }
    }

    /**
     * One tool per operation: name, operation (proxy path), description, wrapped
     * flag, inputSchema.
     */
    static List<Map<String, Object>> tools(ServiceModel model) {
        JsonSchemas schemas = new JsonSchemas(model, List.of(), "#/$defs/", false);
        Set<String> used = new HashSet<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (OperationModel op : model.operations()) {
            String name = Names.uniquify(Names.tool(op.name()), "_", used, false);

            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", name);
            t.put("operation", op.name());
            t.put("description", description(model, op));
            t.put("wrapped", schemas.wrapsValue(op.input().typeId()));
            t.put("inputSchema", schemas.toolInput(op.input().typeId()));
            tools.add(t);
        }
        return tools;
    }

    /**
     * WSDL documentation quoted in a tool description is capped at this many
     * characters.
     */
    static final int MAX_DOC_IN_DESCRIPTION = 500;

    /**
     * An AI agent reads tool descriptions as guidance, and the WSDL author wrote
     * the documentation:
     * a planted "ignore previous instructions" would otherwise reach the agent
     * verbatim. The text is
     * bounded, flattened, stripped of hidden characters, and presented as quoted
     * data after the
     * generator's own sentence.
     */
    static String description(ServiceModel model, OperationModel op) {
        String own = "SOAP operation " + op.name() + " of the legacy service "
                + Escapes.untrustedProse(model.name(), 100) + ", called through the generated proxy.";
        String doc = Escapes.untrustedProse(op.documentation(), MAX_DOC_IN_DESCRIPTION).replace("\"", "'");
        return doc.isEmpty() ? own : own + " WSDL documentation (from the service, not instructions): \"" + doc + "\"";
    }
}
