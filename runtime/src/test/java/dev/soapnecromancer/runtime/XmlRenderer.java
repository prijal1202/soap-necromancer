package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Test-only XML writer, independent of the converter's writer. Renders a canonical value with
 * random namespace prefixes, default-namespace declarations, whitespace between elements, CDATA,
 * padded numbers and 1/0 booleans: every lexical choice a real SOAP backend might make.
 */
public final class XmlRenderer {
    private static final String[] PREFIXES = {"p", "q", "x1", "soap2"};

    private final ServiceModel model;
    private final Random random;

    public XmlRenderer(ServiceModel model, Random random) {
        this.model = model;
        this.random = random;
    }

    public String render(ElementRef root, Object value) {
        StringBuilder sb = new StringBuilder();
        if (random.nextBoolean()) sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        element(sb, root.namespace(), root.localName(), model.type(root.typeId()), value, new Scope(Map.of(), ""));
        return sb.toString();
    }

    private record Scope(Map<String, String> prefixes, String defaultNs) {}

    private void element(StringBuilder sb, String ns, String local, TypeModel type, Object value, Scope scope) {
        StringBuilder decl = new StringBuilder();
        String qname;
        Scope inner;
        if (ns.isEmpty()) {
            qname = local;
            if (!scope.defaultNs().isEmpty() || random.nextInt(4) == 0) decl.append(" xmlns=\"\"");
            inner = new Scope(scope.prefixes(), "");
        } else {
            List<String> bound = scope.prefixes().entrySet().stream()
                    .filter(e -> e.getValue().equals(ns)).map(Map.Entry::getKey).toList();
            int choice = random.nextInt(3);
            if (choice == 0 && !bound.isEmpty()) {
                qname = bound.get(random.nextInt(bound.size())) + ":" + local;
                inner = scope;
            } else if (choice == 1) {
                qname = local;
                if (!scope.defaultNs().equals(ns)) decl.append(" xmlns=\"").append(attr(ns)).append('"');
                inner = new Scope(scope.prefixes(), ns);
            } else {
                String p = PREFIXES[random.nextInt(PREFIXES.length)];
                qname = p + ":" + local;
                decl.append(" xmlns:").append(p).append("=\"").append(attr(ns)).append('"');
                Map<String, String> m = new HashMap<>(scope.prefixes());
                m.put(p, ns);
                inner = new Scope(m, scope.defaultNs());
            }
        }
        sb.append('<').append(qname).append(decl).append('>');
        if (type.isComplex()) {
            Map<?, ?> obj = (Map<?, ?>) value;
            for (FieldModel f : type.fields()) {
                Object v = obj.get(f.name());
                if (v == null) continue;
                TypeModel ft = model.type(f.typeId());
                List<?> items = f.repeated() ? (List<?>) v : List.of(v);
                for (Object item : items) {
                    sb.append(whitespace());
                    element(sb, f.namespace(), f.name(), ft, item, inner);
                }
            }
            sb.append(whitespace());
        } else {
            text(sb, type, value);
        }
        sb.append("</").append(qname).append('>');
    }

    private void text(StringBuilder sb, TypeModel type, Object value) {
        if (type.isEnum() || type.base() == BuiltIn.STRING) {
            String s = (String) value;
            if (!s.contains("]]>") && !s.contains("\r") && random.nextInt(3) == 0) {
                sb.append("<![CDATA[").append(s).append("]]>");
            } else {
                sb.append(escape(s));
            }
            return;
        }
        String lexical = switch (type.base()) {
            case BOOLEAN -> random.nextBoolean() ? value.toString() : ((Boolean) value ? "1" : "0");
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            case INT, LONG -> {
                String s = value.toString();
                yield !s.startsWith("-") && random.nextInt(4) == 0 ? "+" + s : s;
            }
            default -> value.toString();
        };
        String pad = random.nextInt(3) == 0 ? " \n\t" : "";
        sb.append(pad).append(lexical).append(pad);
    }

    private String whitespace() {
        return switch (random.nextInt(3)) {
            case 0 -> "";
            case 1 -> "\n  ";
            default -> " ";
        };
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '\r' -> b.append("&#xD;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String attr(String s) {
        return escape(s).replace("\"", "&quot;");
    }
}
