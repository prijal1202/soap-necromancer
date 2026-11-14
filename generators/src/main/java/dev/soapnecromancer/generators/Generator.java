package dev.soapnecromancer.generators;

import dev.soapnecromancer.core.ModelJson;
import dev.soapnecromancer.core.ParseResult;
import dev.soapnecromancer.core.Warning;
import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import dev.soapnecromancer.runtime.model.SoapVersion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generator's single entry point: parse result -> every generated file.
 * Pure and in-memory:
 * no disk, no network, no state. Generators spec, Requirement 5.
 */
public final class Generator {
    /** Generators Requirement 5.6. The bank WSDL produces about 0.3 MB. */
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 16L * 1024 * 1024;
    public static final int DEFAULT_MAX_TOOL_DEFINITIONS = 20_000;

    private final long maxOutputBytes;
    private final int maxToolDefinitions;

    public Generator() {
        this(DEFAULT_MAX_OUTPUT_BYTES, DEFAULT_MAX_TOOL_DEFINITIONS);
    }

    public Generator(long maxOutputBytes, int maxToolDefinitions) {
        this.maxOutputBytes = maxOutputBytes;
        this.maxToolDefinitions = maxToolDefinitions;
    }

    public GeneratedFiles generate(ParseResult result) {
        ServiceModel model = result.model();
        // Checked before anything is built: MCP tool schemas grow as operations x
        // reachable types.
        McpServerGenerator.checkSchemaSize(model, maxToolDefinitions);
        GeneratedFiles out = new GeneratedFiles(maxOutputBytes);
        out.add("model.json", ModelJson.write(model));
        out.add("openapi.yaml", new OpenApiGenerator().generate(model));
        out.add("generator-report.json", ModelJson.write(report(model, result.warnings())));
        new ProxyGenerator().generate(model, out);
        new TestGenerator().generate(model, out);
        new McpServerGenerator().generate(model, out);
        out.add("README.md", readme(model, result.warnings()));
        return out;
    }

    private static Map<String, Object> report(ServiceModel model, List<Warning> warnings) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generator", "SOAP Necromancer");
        r.put("version", Versions.GENERATOR);
        r.put("service", model.name());
        r.put("soapVersion", model.soapVersion().label());
        r.put("operations", model.operations().stream().map(OperationModel::name).toList());
        r.put("warnings", warnings);
        return r;
    }

    private static String readme(ServiceModel model, List<Warning> warnings) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("serviceName", Escapes.markdown(model.name()));
        ctx.put("generatorVersion", Versions.GENERATOR);
        ctx.put("operationCount", model.operations().size());
        ctx.put("soapVersionLabel", model.soapVersion().label());
        ctx.put("soap12", model.soapVersion() == SoapVersion.SOAP_12);
        ctx.put("clientFaultName", Faults.client(model.soapVersion()));
        ctx.put("serverFaultName", Faults.server(model.soapVersion()));
        ctx.put("artifactId", ProxyGenerator.artifactId(model));
        ctx.put("mcpArtifactId", McpServerGenerator.artifactId(model));
        String endpoint = ProxyGenerator.defaultEndpoint(model);
        ctx.put("endpointDisplay", endpoint == null ? "(none usable in the WSDL: set SOAP_ENDPOINT)" : endpoint);
        // Operation names are restricted by the parser (parser Requirement 2.17), so
        // they are safe
        // inside the README's code fence as they are; the parser also guarantees one
        // operation.
        ctx.put("firstOperation", model.operations().get(0).name());
        List<Map<String, String>> ops = new ArrayList<>();
        for (OperationModel op : model.operations()) {
            Map<String, String> o = new HashMap<>();
            o.put("name", Escapes.markdownCode(op.name()));
            // null, not "": an empty string still opens a Mustache section and leaves a
            // dangling ": ".
            o.put("doc", op.documentation().isBlank() ? null : Escapes.markdown(op.documentation()));
            ops.add(o);
        }
        ctx.put("operations", ops);
        List<Map<String, String>> ws = new ArrayList<>();
        for (Warning w : warnings) {
            ws.add(Map.of("code", w.code(), "location", Escapes.markdownCode(w.location()), "message",
                    Escapes.markdown(w.message())));
        }
        ctx.put("warnings", ws);
        ctx.put("hasWarnings", !ws.isEmpty());
        return Templates.render("README.md", ctx);
    }
}
