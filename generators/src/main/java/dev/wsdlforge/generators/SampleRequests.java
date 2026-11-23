package dev.wsdlforge.generators;

import dev.wsdlforge.runtime.model.BuiltIn;
import dev.wsdlforge.runtime.model.ElementRef;
import dev.wsdlforge.runtime.model.FieldModel;
import dev.wsdlforge.runtime.model.ServiceModel;
import dev.wsdlforge.runtime.model.TypeModel;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one readable, valid sample request per operation for the in-browser
 * preview (generators Requirement 8.3). Required fields are always present, so
 * the converter accepts the sample; optional fields are added near the root
 * while a small budget lasts. Every decimal gets {@link #DECIMAL}, so the
 * preview can show it arriving in the SOAP envelope digit for digit.
 *
 * <p>
 * Bounded whatever the WSDL: a sample whose required content alone exceeds
 * {@link #MAX_NODES} values is refused with {@link TooLarge}, never built.
 */
final class SampleRequests {
    static final BigDecimal DECIMAL = new BigDecimal("1234567890.123456789");
    /** Values in one sample, required ones included. */
    static final int MAX_NODES = 2_000;
    /** Optional fields added to one sample. */
    static final int MAX_OPTIONAL = 40;
    /** Optional fields are only added this close to the root. */
    static final int OPTIONAL_DEPTH = 3;

    /** The sample would be too large to show; carries the reason. */
    static final class TooLarge extends RuntimeException {
        TooLarge(String reason) {
            super(reason, null, false, false);
        }
    }

    /** A sample request: plain JSON values, as the converter takes them. */
    record Sample(Object json, List<String> decimalPaths) {
    }

    private final ServiceModel model;
    private int nodes;
    private int optional;
    private int strings;
    private final List<String> decimalPaths = new ArrayList<>();
    private final Deque<String> onPath = new ArrayDeque<>();

    private SampleRequests(ServiceModel model) {
        this.model = model;
    }

    static Sample build(ServiceModel model, ElementRef root) {
        SampleRequests b = new SampleRequests(model);
        Object json = b.value(model.type(root.typeId()), "$", 0);
        return new Sample(json, List.copyOf(b.decimalPaths));
    }

    private Object value(TypeModel type, String path, int depth) {
        if (++nodes > MAX_NODES)
            throw new TooLarge("its required fields alone need more than " + MAX_NODES + " values");
        if (depth > 64)
            throw new TooLarge("it nests deeper than 64 levels");
        if (!type.isComplex())
            return scalar(type, path);
        if (onPath.contains(type.id()))
            throw new TooLarge("type " + type.id() + " contains itself through required fields");
        onPath.push(type.id());
        Map<String, Object> obj = new LinkedHashMap<>();
        for (FieldModel f : type.fields()) {
            TypeModel ft = model.type(f.typeId());
            if (f.optional()) {
                boolean recursive = ft.isComplex() && onPath.contains(ft.id());
                if (recursive || depth >= OPTIONAL_DEPTH || optional >= MAX_OPTIONAL)
                    continue;
                // An optional field that turns out too big is left out; it never sinks the
                // sample.
                int nodesBefore = nodes;
                int decimalsBefore = decimalPaths.size();
                Deque<String> pathBefore = new ArrayDeque<>(onPath);
                try {
                    obj.put(f.name(), field(f, ft, path, depth, 1));
                    optional++;
                } catch (TooLarge tooBig) {
                    nodes = nodesBefore;
                    decimalPaths.subList(decimalsBefore, decimalPaths.size()).clear();
                    onPath.clear();
                    onPath.addAll(pathBefore);
                    optional = MAX_OPTIONAL;
                }
            } else {
                obj.put(f.name(), field(f, ft, path, depth, Math.max(f.minOccurs(), 1)));
            }
        }
        onPath.pop();
        return obj;
    }

    private Object field(FieldModel f, TypeModel ft, String path, int depth, int items) {
        String fieldPath = path + "." + f.name();
        if (!f.repeated())
            return value(ft, fieldPath, depth + 1);
        if (items > MAX_NODES)
            throw new TooLarge("field " + f.name() + " needs " + items + " items");
        List<Object> list = new ArrayList<>(items);
        for (int i = 0; i < items; i++)
            list.add(value(ft, fieldPath + "[" + i + "]", depth + 1));
        return list;
    }

    private Object scalar(TypeModel type, String path) {
        if (type.isEnum())
            return type.enumValues().get(0);
        BuiltIn base = type.baseOrString();
        return switch (base) {
            // Numbered, so two id fields never get the same value (a bank refuses a
            // transfer to itself).
            case STRING -> "example-" + ++strings;
            case INT, LONG, INTEGER -> 42;
            case DECIMAL -> {
                decimalPaths.add(path);
                yield DECIMAL;
            }
            case BOOLEAN -> true;
            case DATE -> "2026-01-31";
            case DATE_TIME -> "2026-01-31T10:15:30Z";
        };
    }

    /**
     * Two-space indented JSON, written by hand so decimals keep their exact text
     * (no exponent, no float).
     */
    static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, "");
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, String indent) {
        String inner = indent + "  ";
        switch (value) {
            case Map<?, ?> m when m.isEmpty() -> out.append("{}");
            case Map<?, ?> m -> {
                out.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.append(inner);
                    quote(out, String.valueOf(e.getKey()));
                    out.append(": ");
                    write(out, e.getValue(), inner);
                    out.append(++i < m.size() ? ",\n" : "\n");
                }
                out.append(indent).append('}');
            }
            case List<?> l when l.isEmpty() -> out.append("[]");
            case List<?> l -> {
                out.append("[\n");
                for (int i = 0; i < l.size(); i++) {
                    out.append(inner);
                    write(out, l.get(i), inner);
                    out.append(i + 1 < l.size() ? ",\n" : "\n");
                }
                out.append(indent).append(']');
            }
            case String s -> quote(out, s);
            case BigDecimal d -> out.append(d.toPlainString());
            case Number n -> out.append(n);
            case Boolean b -> out.append(b);
            default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass());
        }
    }

    /** RFC 8259 string escaping; field names come from the WSDL. */
    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029)
                        out.append(String.format("\\u%04x", (int) c));
                    else
                        out.append(c);
                }
            }
        }
        out.append('"');
    }
}
