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
    private final ServiceModel model;
    private final Map<ElementRef, List<String>> namespacesByRoot = new ConcurrentHashMap<>();

    public JsonXmlConverter(ServiceModel model) {
        this.model = Objects.requireNonNull(model);
    }

    public ServiceModel model() {
        return model;
    }

    // ---------------------------------------------------------------- JSON -> XML

    public String toXml(ElementRef root, Object json) {
        List<String> namespaces = namespacesByRoot.computeIfAbsent(root, this::collectNamespaces);
        Map<String, String> prefixes = new HashMap<>();
        for (int i = 0; i < namespaces.size(); i++)
            prefixes.put(namespaces.get(i), "ns" + (i + 1));

        StringBuilder decl = new StringBuilder();
        for (String ns : namespaces) {
            decl.append(" xmlns:").append(prefixes.get(ns)).append("=\"");
            RuntimeXml.appendEscaped(decl, ns, true);
            decl.append('"');
        }
        StringBuilder out = new StringBuilder(256);
        if (json == null)
            throw new ConversionException("$", "request body is required");
        writeElement(out, qname(prefixes, root.namespace(), root.localName()), decl.toString(),
                model.type(root.typeId()), json, "$", prefixes);
        return out.toString();
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
            String path, Map<String, String> prefixes) {
        out.append('<').append(qname).append(nsDecl).append('>');
        if (type.isComplex()) {
            writeComplex(out, type, value, path, prefixes);
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
            Map<String, String> prefixes) {
        if (!(value instanceof Map<?, ?> obj)) {
            throw new ConversionException(path, "expected a JSON object, got " + describe(value));
        }
        Set<String> known = new HashSet<>();
        for (FieldModel f : type.fields())
            known.add(f.name());
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
                    writeElement(out, qn, "", ft, item, fieldPath + "[" + i + "]", prefixes);
                }
            } else if (v == null) {
                if (!f.optional())
                    throw new ConversionException(fieldPath, "required field is missing");
            } else {
                writeElement(out, qn, "", ft, v, fieldPath, prefixes);
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
        BuiltIn base = type.base() == null ? BuiltIn.STRING : type.base();
        return switch (base) {
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
            case DATE -> temporal(v, path, DateTimeFormatter.ISO_DATE, "an ISO-8601 date (2024-01-31)");
            case DATE_TIME ->
                temporal(v, path, DateTimeFormatter.ISO_DATE_TIME, "an ISO-8601 date-time (2024-01-31T10:15:30Z)");
        };
    }

    private static String integral(Object v, String path, BigInteger min, BigInteger max, String xsType) {
        BigDecimal d = decimal(v, path);
        BigInteger i;
        try {
            i = d.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new ConversionException(path, "expected an integer for " + xsType + ", got " + d.toPlainString());
        }
        if (i.compareTo(min) < 0 || i.compareTo(max) > 0) {
            throw new ConversionException(path, i + " is out of range for " + xsType);
        }
        return i.toString();
    }

    private static BigDecimal decimal(Object v, String path) {
        try {
            if (v instanceof BigDecimal d)
                return d;
            if (v instanceof BigInteger i)
                return new BigDecimal(i);
            if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                return BigDecimal.valueOf(((Number) v).longValue());
            }
            if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d))
                    throw new ConversionException(path, "NaN and Infinity are not numbers");
                return BigDecimal.valueOf(d);
            }
            if (v instanceof String s)
                return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new ConversionException(path, "expected a number, got \"" + v + "\"");
        }
        throw new ConversionException(path, "expected a number, got " + describe(v));
    }

    private static String temporal(Object v, String path, DateTimeFormatter format, String expected) {
        if (v instanceof String s) {
            try {
                format.parse(s);
                return s;
            } catch (DateTimeParseException e) {
                // fall through
            }
        }
        throw new ConversionException(path, "expected " + expected + ", got " + describe(v));
    }

    private static String describe(Object v) {
        if (v == null)
            return "null";
        if (v instanceof String s)
            return "string \"" + (s.length() > 40 ? s.substring(0, 40) + "..." : s) + "\"";
        if (v instanceof Map)
            return "an object";
        if (v instanceof List)
            return "an array";
        if (v instanceof Boolean)
            return "boolean " + v;
        if (v instanceof Number)
            return "number " + v;
        return v.getClass().getSimpleName();
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
                    + " but found {" + nsOf(element) + "}" + element.getLocalName());
        }
        return read(element, model.type(root.typeId()), "$");
    }

    private Object read(Element el, TypeModel type, String path) {
        if (!type.isComplex())
            return readSimple(el.getTextContent(), type, path);

        Map<String, List<Element>> children = new HashMap<>();
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) {
                children.computeIfAbsent("{" + nsOf(c) + "}" + c.getLocalName(), k -> new ArrayList<>()).add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldModel f : type.fields()) {
            List<Element> matches = children.getOrDefault("{" + f.namespace() + "}" + f.name(), List.of());
            String fieldPath = path + "." + f.name();
            TypeModel ft = model.type(f.typeId());
            if (f.repeated()) {
                List<Object> items = new ArrayList<>(matches.size());
                for (int i = 0; i < matches.size(); i++)
                    items.add(read(matches.get(i), ft, fieldPath + "[" + i + "]"));
                out.put(f.name(), items);
            } else if (matches.size() > 1) {
                throw new ConversionException(fieldPath,
                        "element occurs " + matches.size() + " times but the schema allows one");
            } else if (matches.size() == 1) {
                out.put(f.name(), read(matches.get(0), ft, fieldPath));
            }
        }
        return out;
    }

    private static Object readSimple(String raw, TypeModel type, String path) {
        if (type.isEnum())
            return raw;
        BuiltIn base = type.base() == null ? BuiltIn.STRING : type.base();
        if (base == BuiltIn.STRING)
            return raw;
        String text = raw.trim();
        try {
            return switch (base) {
                case INT -> Integer.valueOf(stripPlus(text));
                case LONG -> Long.valueOf(stripPlus(text));
                case DECIMAL -> new BigDecimal(text);
                case BOOLEAN -> switch (text) {
                    case "true", "1" -> Boolean.TRUE;
                    case "false", "0" -> Boolean.FALSE;
                    default -> throw new ConversionException(path, "not an xs:boolean: \"" + text + "\"");
                };
                default -> text; // DATE, DATE_TIME: keep the lexical form
            };
        } catch (NumberFormatException e) {
            throw new ConversionException(path, "not a valid " + base.typeId() + ": \"" + text + "\"");
        }
    }

    private static String stripPlus(String s) {
        return s.startsWith("+") ? s.substring(1) : s;
    }

    private static String nsOf(Element e) {
        return e.getNamespaceURI() == null ? "" : e.getNamespaceURI();
    }
}
