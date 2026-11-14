package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.BuiltIn;
import dev.soapnecromancer.runtime.model.ElementRef;
import dev.soapnecromancer.runtime.model.FieldModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import dev.soapnecromancer.runtime.model.TypeModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model-driven JSON <-> XML conversion for SOAP body payloads. Shipped inside
 * every generated proxy.
 * JSON values are plain Java (Map, List, String, Number, Boolean) so any JSON
 * library can feed it.
 * See .kiro/specs/converter/requirements.md.
 */
public final class JsonXmlConverter {
    /** Converter Requirements 1.14 and 2.8. */
    public static final int MAX_DEPTH = RuntimeXml.MAX_ELEMENT_DEPTH;
    /**
     * Converter Requirement 1.8: digits before the point, and the scale, are
     * bounded by this.
     */
    public static final int MAX_NUMBER_DIGITS = 1000;
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    /** Error messages quote at most this much of a value (Requirement 2.12). */
    private static final int QUOTE = 40;

    /**
     * Namespace prefixes and their declarations for one root element, computed
     * once.
     */
    private record RootPlan(Map<String, String> prefixes, String declarations) {
    }

    private final ServiceModel model;
    private final Map<ElementRef, RootPlan> plans = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fieldNames = new ConcurrentHashMap<>();

    public JsonXmlConverter(ServiceModel model) {
        this.model = Objects.requireNonNull(model);
    }

    public ServiceModel model() {
        return model;
    }

    // ---------------------------------------------------------------- JSON -> XML

    public String toXml(ElementRef root, Object json) {
        if (json == null)
            throw new ConversionException("$", "request body is required");
        RootPlan plan = plans.computeIfAbsent(root, this::plan);
        StringBuilder out = new StringBuilder(256);
        writeElement(out, qname(plan.prefixes(), root.namespace(), root.localName()), plan.declarations(),
                model.type(root.typeId()), json, "$", plan.prefixes(), 1);
        return out.toString();
    }

    private RootPlan plan(ElementRef root) {
        List<String> namespaces = collectNamespaces(root);
        Map<String, String> prefixes = new HashMap<>();
        StringBuilder decl = new StringBuilder();
        for (int i = 0; i < namespaces.size(); i++) {
            String ns = namespaces.get(i);
            String prefix = "ns" + (i + 1);
            prefixes.put(ns, prefix);
            decl.append(" xmlns:").append(prefix).append("=\"");
            if (RuntimeXml.appendEscaped(decl, ns, true) >= 0)
                throw new IllegalStateException("namespace contains a character not allowed in XML: " + ns);
            decl.append('"');
        }
        return new RootPlan(Map.copyOf(prefixes), decl.toString());
    }

    private List<String> collectNamespaces(ElementRef root) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        if (!root.namespace().isEmpty())
            found.add(root.namespace());
        Deque<String> todo = new ArrayDeque<>(List.of(root.typeId()));
        Set<String> visited = new HashSet<>();
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (!visited.add(id))
                continue;
            for (FieldModel f : model.type(id).fields()) {
                if (!f.namespace().isEmpty())
                    found.add(f.namespace());
                todo.push(f.typeId());
            }
        }
        return List.copyOf(found);
    }

    private static String qname(Map<String, String> prefixes, String ns, String local) {
        return ns.isEmpty() ? local : prefixes.get(ns) + ":" + local;
    }

    private void writeElement(StringBuilder out, String qname, String nsDecl, TypeModel type, Object value,
            String path, Map<String, String> prefixes, int depth) {
        if (depth > MAX_DEPTH)
            throw new ConversionException(path, "value nests deeper than " + MAX_DEPTH + " levels");
        out.append('<').append(qname).append(nsDecl).append('>');
        if (type.isComplex()) {
            writeComplex(out, type, value, path, prefixes, depth);
        } else {
            String text = lexical(type, value, path);
            int bad = RuntimeXml.appendEscaped(out, text, false);
            if (bad >= 0) {
                throw new ConversionException(path, String.format("character U+%04X at index %d is not allowed in XML",
                        text.codePointAt(bad), bad));
            }
        }
        out.append("</").append(qname).append('>');
    }

    private void writeComplex(StringBuilder out, TypeModel type, Object value, String path,
            Map<String, String> prefixes, int depth) {
        if (!(value instanceof Map<?, ?> obj)) {
            throw new ConversionException(path, "expected a JSON object, got " + describe(value));
        }
        Set<String> known = fieldNames.computeIfAbsent(type.id(),
                id -> Set.copyOf(type.fields().stream().map(FieldModel::name).toList()));
        for (Object key : obj.keySet()) {
            if (!known.contains(String.valueOf(key))) {
                throw new ConversionException(path + "." + key,
                        "unknown field; allowed fields: " + known.stream().sorted().toList());
            }
        }
        for (FieldModel f : type.fields()) {
            Object v = obj.get(f.name());
            String fieldPath = path + "." + f.name();
            String qn = qname(prefixes, f.namespace(), f.name());
            TypeModel ft = model.type(f.typeId());
            if (f.repeated()) {
                if (v == null) {
                    if (f.minOccurs() > 0)
                        throw new ConversionException(fieldPath, "required array is missing");
                    continue;
                }
                if (!(v instanceof List<?> list)) {
                    throw new ConversionException(fieldPath, "expected a JSON array, got " + describe(v));
                }
                if (list.size() < f.minOccurs()
                        || (f.maxOccurs() != FieldModel.UNBOUNDED && list.size() > f.maxOccurs())) {
                    throw new ConversionException(fieldPath, "expected " + f.minOccurs() + ".."
                            + (f.maxOccurs() == FieldModel.UNBOUNDED ? "*" : f.maxOccurs()) + " items, got "
                            + list.size());
                }
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item == null)
                        throw new ConversionException(fieldPath + "[" + i + "]", "null items are not allowed");
                    writeElement(out, qn, "", ft, item, fieldPath + "[" + i + "]", prefixes, depth + 1);
                }
            } else if (v == null) {
                if (!f.optional())
                    throw new ConversionException(fieldPath, "required field is missing");
            } else {
                writeElement(out, qn, "", ft, v, fieldPath, prefixes, depth + 1);
            }
        }
    }

    private static String lexical(TypeModel type, Object v, String path) {
        if (type.isEnum()) {
            if (!(v instanceof String s) || !type.enumValues().contains(s)) {
                throw new ConversionException(path, "expected one of " + type.enumValues() + ", got " + describe(v));
            }
            return s;
        }
        return switch (type.baseOrString()) {
            case STRING -> {
                if (!(v instanceof String s))
                    throw new ConversionException(path, "expected a string, got " + describe(v));
                yield s;
            }
            case INT -> integral(v, path, BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE),
                    "xs:int");
            case LONG ->
                integral(v, path, BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE), "xs:long");
            case DECIMAL -> decimal(v, path).toPlainString();
            case BOOLEAN -> {
                if (v instanceof Boolean b)
                    yield b.toString();
                if ("true".equals(v) || "false".equals(v))
                    yield (String) v;
                throw new ConversionException(path, "expected a boolean, got " + describe(v));
            }
            case DATE -> temporal(v, path, "an ISO-8601 date (2024-01-31)", DateTimeFormatter.ISO_DATE);
            case DATE_TIME -> temporal(v, path, "an ISO-8601 date-time (2024-01-31T10:15:30Z)",
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        };
    }

    private static String integral(Object v, String path, BigInteger min, BigInteger max, String xsType) {
        BigDecimal d = decimal(v, path);
        // Cheap pre-check: more than 19 integer digits can never fit a long, and
        // toBigIntegerExact
        // would otherwise materialise up to MAX_NUMBER_DIGITS digits.
        if (d.precision() - d.scale() > 19)
            throw new ConversionException(path, "number is out of range for " + xsType);
        BigInteger i;
        try {
            i = d.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new ConversionException(path, "expected an integer for " + xsType + ", got " + quote(d.toString()));
        }
        if (i.compareTo(min) < 0 || i.compareTo(max) > 0) {
            throw new ConversionException(path, i + " is out of range for " + xsType);
        }
        return i.toString();
    }

    private static BigDecimal decimal(Object v, String path) {
        BigDecimal d;
        try {
            d = switch (v) {
                case null -> throw new ConversionException(path, "expected a number, got null");
                case BigDecimal b -> b;
                case BigInteger i -> new BigDecimal(i);
                case Integer i -> BigDecimal.valueOf(i);
                case Long l -> BigDecimal.valueOf(l);
                case Short s -> BigDecimal.valueOf(s);
                case Byte b -> BigDecimal.valueOf(b);
                case Double x -> finite(x, path);
                case Float x -> finite(x, path);
                case String s -> new BigDecimal(s.trim());
                default -> throw new ConversionException(path, "expected a number, got " + describe(v));
            };
        } catch (NumberFormatException e) {
            throw new ConversionException(path, "expected a number, got " + describe(v));
        }
        return bounded(d, path);
    }

    private static BigDecimal finite(double d, String path) {
        if (Double.isNaN(d) || Double.isInfinite(d))
            throw new ConversionException(path, "NaN and Infinity are not numbers");
        return BigDecimal.valueOf(d);
    }

    /**
     * Requirement 1.8 and 2.10: rejects values whose plain form or arithmetic would
     * be huge.
     */
    private static BigDecimal bounded(BigDecimal d, String path) {
        if (d.scale() > MAX_NUMBER_DIGITS || d.scale() < -MAX_NUMBER_DIGITS
                || d.precision() - d.scale() > MAX_NUMBER_DIGITS) {
            throw new ConversionException(path, "number is too large or too precise (more than "
                    + MAX_NUMBER_DIGITS + " digits)");
        }
        return d;
    }

    private static String temporal(Object v, String path, String expected, DateTimeFormatter... formats) {
        if (v instanceof String s) {
            for (DateTimeFormatter format : formats) {
                try {
                    format.parse(s);
                    return s;
                } catch (DateTimeParseException e) {
                    // try the next form
                }
            }
        }
        throw new ConversionException(path, "expected " + expected + ", got " + describe(v));
    }

    private static String describe(Object v) {
        return switch (v) {
            case null -> "null";
            case String s -> "string " + quote(s);
            case Map<?, ?> m -> "an object";
            case List<?> l -> "an array";
            case Boolean b -> "boolean " + b;
            case Number n -> "number " + quote(n.toString());
            default -> v.getClass().getSimpleName();
        };
    }

    /** At most {@link #QUOTE} characters of a value, quoted. */
    private static String quote(String s) {
        return "\"" + (s.length() > QUOTE ? s.substring(0, QUOTE) + "..." : s) + "\"";
    }

    // ---------------------------------------------------------------- XML -> JSON

    public Object toJson(ElementRef root, String xml) {
        Document doc;
        try {
            doc = RuntimeXml.parse(xml);
        } catch (SAXException e) {
            throw new ConversionException("$", "invalid XML: " + e.getMessage());
        }
        return toJson(root, doc.getDocumentElement());
    }

    public Object toJson(ElementRef root, Element element) {
        if (!root.localName().equals(element.getLocalName()) || !root.namespace().equals(nsOf(element))) {
            throw new ConversionException("$", "expected element {" + root.namespace() + "}" + root.localName()
                    + " but found an element named " + quote(element.getLocalName()));
        }
        return read(element, model.type(root.typeId()), "$", 1);
    }

    private Object read(Element el, TypeModel type, String path, int depth) {
        if (depth > MAX_DEPTH)
            throw new ConversionException(path, "response nests deeper than " + MAX_DEPTH + " levels");
        if (!type.isComplex())
            return readSimple(el.getTextContent(), type, path);

        Map<String, List<Element>> children = new HashMap<>();
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && !isNil(c)) {
                children.computeIfAbsent("{" + nsOf(c) + "}" + c.getLocalName(), k -> new ArrayList<>()).add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldModel f : type.fields()) {
            List<Element> matches = children.getOrDefault("{" + f.namespace() + "}" + f.name(), List.of());
            TypeModel ft = model.type(f.typeId());
            if (f.repeated()) {
                List<Object> items = new ArrayList<>(matches.size());
                for (int i = 0; i < matches.size(); i++)
                    items.add(read(matches.get(i), ft, path + "." + f.name() + "[" + i + "]", depth + 1));
                out.put(f.name(), items);
            } else if (matches.size() > 1) {
                throw new ConversionException(path + "." + f.name(),
                        "element occurs " + matches.size() + " times but the schema allows one");
            } else if (matches.size() == 1) {
                out.put(f.name(), read(matches.get(0), ft, path + "." + f.name(), depth + 1));
            }
        }
        return out;
    }

    /**
     * Requirement 2.9: {@code xsi:nil="true"} (or "1") means the element is absent.
     */
    private static boolean isNil(Element e) {
        String nil = e.getAttributeNS(XSI, "nil").strip();
        return nil.equals("true") || nil.equals("1");
    }

    private static Object readSimple(String raw, TypeModel type, String path) {
        if (type.isEnum())
            return raw;
        BuiltIn base = type.baseOrString();
        String text = raw.strip();
        try {
            return switch (base) {
                case STRING -> raw;
                case INT -> Integer.valueOf(text);
                case LONG -> Long.valueOf(text);
                case DECIMAL -> bounded(new BigDecimal(text), path);
                case BOOLEAN -> switch (text) {
                    case "true", "1" -> Boolean.TRUE;
                    case "false", "0" -> Boolean.FALSE;
                    default -> throw new ConversionException(path, "not an xs:boolean: " + quote(text));
                };
                case DATE, DATE_TIME -> text; // keep the lexical form
            };
        } catch (NumberFormatException e) {
            throw new ConversionException(path, "not a valid " + base.typeId() + ": " + quote(text));
        }
    }

    private static String nsOf(Element e) {
        return e.getNamespaceURI() == null ? "" : e.getNamespaceURI();
    }
}
