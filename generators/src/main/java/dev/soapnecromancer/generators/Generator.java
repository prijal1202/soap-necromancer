package dev.soapnecromancer.generators;

import dev.soapnecromancer.core.ModelJson;
import dev.soapnecromancer.core.ParseResult;
import dev.soapnecromancer.core.Warning;
import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;

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

    public GeneratedFiles generate(ParseResult result) {
        ServiceModel model = result.model();
        GeneratedFiles out = new GeneratedFiles();
        out.add("model.json", ModelJson.write(model));
        out.add("openapi.yaml", new OpenApiGenerator().generate(model));
        out.add("generator-report.json", ModelJson.write(report(model, result.warnings())));
        new ProxyGenerator().generate(model, out);
        out.add("README.md", readme(model, result.warnings()));
        return out;
    }

    private static Map<String, Object> report(ServiceModel model, List<Warning> warnings) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generator", "SOAP Necromancer");
        r.put("version", Versions.GENERATOR);
        r.put("service", model.name());
        r.put("operations", model.operations().stream().map(OperationModel::name).toList());
        r.put("warnings", warnings);
        return r;
    }

    private static String readme(ServiceModel model, List<Warning> warnings) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("serviceName", Escapes.markdown(model.name()));
        ctx.put("generatorVersion", Versions.GENERATOR);
        ctx.put("operationCount", model.operations().size());
        ctx.put("artifactId", ProxyGenerator.artifactId(model));
        String endpoint = ProxyGenerator.defaultEndpoint(model);
        ctx.put("endpointDisplay", endpoint == null ? "(none usable in the WSDL: set SOAP_ENDPOINT)" : endpoint);
        ctx.put("firstOperation", model.operations().get(0).name());
        List<Map<String, String>> ops = new ArrayList<>();
        for (OperationModel op : model.operations()) {
            Map<String, String> o = new HashMap<>();
            o.put("name", Escapes.markdown(op.name()));
            o.put("doc", Escapes.markdown(op.documentation()));
            ops.add(o);
        }
        ctx.put("operations", ops);
        List<Map<String, String>> ws = new ArrayList<>();
        for (Warning w : warnings) {
            ws.add(Map.of("code", w.code(), "location", Escapes.markdown(w.location()), "message",
                    Escapes.markdown(w.message())));
        }
        ctx.put("warnings", ws);
        ctx.put("hasWarnings", !ws.isEmpty());
        return Templates.render("README.md", ctx);
    }
}
