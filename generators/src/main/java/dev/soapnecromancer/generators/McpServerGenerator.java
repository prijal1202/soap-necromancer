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
 * Generator: canonical model -> {@code mcp-server/**}, the generated MCP server (generators spec,
 * Requirement 4). A plain Java 21 project on the MCP Java SDK, stdio transport, one tool per
 * operation. Tool definitions are data ({@code tools.json}), so one small template serves every WSDL.
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
        ctx.put("serviceNameXml", Escapes.xmlText(model.name()));
        ctx.put("serviceNameDoc", Escapes.javaComment(model.name()));
        ctx.put("serverNameJava", Escapes.javaString(model.name()));
        ctx.put("mcpKey", Names.artifactId(model.name(), "soap"));
        List<Map<String, String>> readmeTools = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            readmeTools.add(Map.of("name", (String) t.get("name"), "description", Escapes.markdown((String) t.get("description"))));
        }
        ctx.put("tools", readmeTools);

        out.add(ROOT + "pom.xml", Templates.render("mcp/pom.xml", ctx));
        out.add(ROOT + "README.md", Templates.render("mcp/README.md", ctx));
        out.add(ROOT + "src/main/java/" + pkg.replace('.', '/') + "/McpServerMain.java",
                Templates.render("mcp/McpServerMain.java", ctx));
        out.add(ROOT + "src/main/resources/tools.json", ModelJson.write(tools));
        out.add(ROOT + "src/main/resources/simplelogger.properties", Templates.render("mcp/simplelogger.properties", ctx));
    }

    public static String artifactId(ServiceModel model) {
        return Names.artifactId(model.name(), "mcp-server");
    }

    /** One tool per operation: name, operation (proxy path), description, wrapped flag, inputSchema. */
    static List<Map<String, Object>> tools(ServiceModel model) {
        JsonSchemas schemas = new JsonSchemas(model, List.of(), "#/$defs/", false);
        Set<String> used = new HashSet<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (OperationModel op : model.operations()) {
            String base = Names.tool(op.name());
            String name = base;
            for (int i = 2; !used.add(name); i++) name = base + "_" + i;

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

    private static String description(ServiceModel model, OperationModel op) {
        String doc = op.documentation().isEmpty() ? "" : op.documentation() + " ";
        return doc + "(SOAP operation " + op.name() + " of the legacy service " + model.name()
                + ", called through the generated proxy.)";
    }
}
