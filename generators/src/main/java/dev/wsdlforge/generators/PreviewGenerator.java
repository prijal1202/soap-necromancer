package dev.wsdlforge.generators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.wsdlforge.core.ModelJson;
import dev.wsdlforge.runtime.ConversionException;
import dev.wsdlforge.runtime.JsonXmlConverter;
import dev.wsdlforge.runtime.SoapClient;
import dev.wsdlforge.runtime.model.OperationModel;
import dev.wsdlforge.runtime.model.ServiceModel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generator: canonical model -> {@code preview.json}, the data the UI needs to
 * show the zip without unpacking anything by hand (generators Requirement 8):
 * the OpenAPI document as JSON, the Kiro {@code mcp.json} entry, and per
 * operation a sample request with the exact SOAP envelope and headers the
 * generated proxy sends for it. Envelope and headers come from the runtime's
 * own {@link JsonXmlConverter} and {@link SoapClient}, the code every generated
 * proxy ships, so the preview cannot drift from the real behaviour.
 *
 * <p>
 * The preview is a convenience. It never makes a generation fail: past its
 * budget it is cut down, and when even that does not fit the output limit it is
 * replaced by a short note.
 */
final class PreviewGenerator {
    static final String PATH = "preview.json";
    static final int FORMAT = 1;
    /** Sample envelopes and request texts together, across all operations. */
    static final int MAX_SAMPLE_BYTES = 2 * 1024 * 1024;

    private static final com.fasterxml.jackson.databind.ObjectWriter COMPACT = ModelJson.mapper().writer()
            .without(SerializationFeature.INDENT_OUTPUT);

    void generate(ServiceModel model, GeneratedFiles out) {
        String full = write(preview(model));
        if (full.getBytes(StandardCharsets.UTF_8).length + 1 <= out.remainingBytes()) {
            out.add(PATH, full + "\n");
            return;
        }
        Map<String, Object> note = header(model);
        note.put("truncated", "The preview would push the zip past its size limit, so it was left out."
                + " Every generated file is still in the zip.");
        String small = write(note) + "\n";
        if (small.getBytes(StandardCharsets.UTF_8).length <= out.remainingBytes())
            out.add(PATH, small);
    }

    Map<String, Object> preview(ServiceModel model) {
        Map<String, Object> p = header(model);
        p.put("openapi", new OpenApiGenerator().document(model));
        p.put("mcp", mcp(model));
        JsonXmlConverter converter = new JsonXmlConverter(model);
        List<Map<String, Object>> samples = new ArrayList<>();
        long used = 0;
        for (OperationModel op : model.operations()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("operation", op.name());
            s.put("path", "/" + op.name());
            if (used >= MAX_SAMPLE_BYTES) {
                s.put("omitted", "Left out: the preview's " + MAX_SAMPLE_BYTES / (1024 * 1024)
                        + " MB budget for samples is used up.");
                samples.add(s);
                continue;
            }
            try {
                SampleRequests.Sample sample = SampleRequests.build(model, op.input());
                String request = SampleRequests.toJson(sample.json());
                String envelope = SoapClient.envelope(model.soapVersion(), converter.toXml(op.input(), sample.json()));
                s.put("request", request);
                s.put("soapHeaders", SoapClient.requestHeaders(model.soapVersion(), op));
                s.put("envelope", envelope);
                s.put("decimalPaths", sample.decimalPaths());
                used += request.length() + envelope.length();
            } catch (SampleRequests.TooLarge e) {
                s.put("omitted", "No sample: " + e.getMessage() + ".");
            } catch (ConversionException e) {
                // Not expected for a sample built from the model; reported rather than failing the zip.
                s.put("omitted", "No sample: the converter refused it at " + e.path() + ".");
            }
            samples.add(s);
        }
        p.put("samples", samples);
        return p;
    }

    private static Map<String, Object> header(ServiceModel model) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("format", FORMAT);
        p.put("service", model.name());
        p.put("soapVersion", model.soapVersion().label());
        p.put("endpoint", ProxyGenerator.defaultEndpoint(model));
        p.put("sampleDecimal", SampleRequests.DECIMAL.toPlainString());
        return p;
    }

    /** The same entry {@code mcp-server/README.md} tells users to add to Kiro's mcp.json. */
    private static Map<String, Object> mcp(ServiceModel model) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("command", "java");
        server.put("args", List.of("-jar", "/absolute/path/to/mcp-server/target/" + McpServerGenerator.jarName(model)));
        server.put("env", Map.of("PROXY_URL", "http://localhost:8080"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverKey", McpServerGenerator.mcpKey(model));
        m.put("jar", McpServerGenerator.jarName(model));
        m.put("kiroConfig", Map.of("mcpServers", Map.of(McpServerGenerator.mcpKey(model), server)));
        return m;
    }

    private static String write(Object value) {
        try {
            return COMPACT.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
