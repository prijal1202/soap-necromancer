package dev.soapnecromancer.generators;

import dev.soapnecromancer.runtime.model.BuiltIn;
import dev.soapnecromancer.runtime.model.FieldModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import dev.soapnecromancer.runtime.model.TypeModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical model -> JSON Schema maps, shared by the OpenAPI generator ({@code #/components/schemas/})
 * and the generated MCP server's tool schemas ({@code #/$defs/}). Generators Requirements 1.4-1.7, 4.3.
 *
 * <p>Complex and enum types become named definitions referenced with {@code $ref}; built-ins and
 * other simple types are inlined. The mapping mirrors what {@code JsonXmlConverter} accepts.
 */
public final class JsonSchemas {
    private final ServiceModel model;
    private final Map<String, String> names;
    private final String refPrefix;
    private final boolean decimalAsString;

    /**
     * @param refPrefix       e.g. {@code #/components/schemas/} or {@code #/$defs/}
     * @param decimalAsString emit decimals as pattern-checked strings (generators Requirement 6.4)
     */
    public JsonSchemas(ServiceModel model, Collection<String> reservedNames, String refPrefix, boolean decimalAsString) {
        this.model = model;
        this.refPrefix = refPrefix;
        this.decimalAsString = decimalAsString;
        List<String> named = new ArrayList<>();
        for (TypeModel t : model.types().values()) {
            if (isNamed(t)) named.add(t.id());
        }
        this.names = Names.unique(named, reservedNames);
    }

    public static boolean isNamed(TypeModel t) {
        return t.isComplex() || t.isEnum();
    }

    /** Type id -> definition name, in model order, for every named type. */
    public Map<String, String> names() {
        return names;
    }

    /** Schema to use where a value of {@code typeId} appears: a {@code $ref} or an inline built-in. */
    public Map<String, Object> reference(String typeId) {
        TypeModel t = model.type(typeId);
        if (isNamed(t)) return map("$ref", refPrefix + names.get(typeId));
        return builtIn(t.base() == null ? BuiltIn.STRING : t.base());
    }

    /** Full definition of a named type, for {@code components/schemas} or {@code $defs}. */
    public Map<String, Object> definition(String typeId) {
        TypeModel t = model.type(typeId);
        if (t.isEnum()) return map("type", "string", "enum", List.copyOf(t.enumValues()));
        if (!t.isComplex()) return reference(typeId);

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (FieldModel f : t.fields()) {
            Map<String, Object> s = reference(f.typeId());
            if (f.repeated()) {
                Map<String, Object> array = map("type", "array", "items", s);
                if (f.minOccurs() > 0) array.put("minItems", f.minOccurs());
                if (f.maxOccurs() != FieldModel.UNBOUNDED) array.put("maxItems", f.maxOccurs());
                s = array;
            }
            properties.put(f.name(), s);
            if (f.minOccurs() >= 1) required.add(f.name());
        }
        Map<String, Object> schema = map("type", "object", "properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> builtIn(BuiltIn b) {
        return switch (b) {
            case STRING -> map("type", "string");
            case INT -> map("type", "integer", "format", "int32");
            case LONG -> map("type", "integer", "format", "int64");
            case DECIMAL -> decimalAsString
                    ? map("type", "string", "format", "decimal", "pattern", "^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$")
                    : map("type", "number", "format", "decimal");
            case BOOLEAN -> map("type", "boolean");
            case DATE -> map("type", "string", "format", "date");
            case DATE_TIME -> map("type", "string", "format", "date-time");
        };
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
