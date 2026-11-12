package dev.soapnecromancer.generators;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Minimal block-style YAML writer for maps, lists, strings, numbers and
 * booleans.
 * Every string value is double-quoted and escaped; keys are plain only when
 * unambiguous.
 * Exists so the generator needs no YAML library (docs/decisions.md section 4).
 */
public final class Yaml {
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$-]*");
    /** Plain scalars YAML 1.1/1.2 would read as booleans or null. */
    private static final Set<String> RESERVED = Set.of("y", "n", "yes", "no", "true", "false", "on", "off", "null");

    private Yaml() {
    }

    public static String write(Map<?, ?> document) {
        StringBuilder sb = new StringBuilder();
        if (document.isEmpty())
            return "{}\n";
        map(sb, document, 0, false);
        return sb.toString();
    }

    private static void map(StringBuilder sb, Map<?, ?> m, int indent, boolean continueLine) {
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(first && continueLine))
                sb.append(" ".repeat(indent));
            first = false;
            sb.append(key(String.valueOf(e.getKey()))).append(':');
            Object v = e.getValue();
            if (v instanceof Map<?, ?> child && !child.isEmpty()) {
                sb.append('\n');
                map(sb, child, indent + 2, false);
            } else if (v instanceof List<?> list && !list.isEmpty()) {
                sb.append('\n');
                list(sb, list, indent + 2, false);
            } else {
                sb.append(' ').append(scalar(v)).append('\n');
            }
        }
    }

    private static void list(StringBuilder sb, List<?> l, int indent, boolean continueLine) {
        boolean first = true;
        for (Object item : l) {
            if (!(first && continueLine))
                sb.append(" ".repeat(indent));
            first = false;
            sb.append("- ");
            if (item instanceof Map<?, ?> m && !m.isEmpty()) {
                map(sb, m, indent + 2, true);
            } else if (item instanceof List<?> nested && !nested.isEmpty()) {
                list(sb, nested, indent + 2, true);
            } else {
                sb.append(scalar(item)).append('\n');
            }
        }
    }

    private static String key(String k) {
        if (PLAIN_KEY.matcher(k).matches() && !RESERVED.contains(k.toLowerCase()))
            return k;
        return quote(k);
    }

    private static String scalar(Object v) {
        if (v == null)
            return "null";
        if (v instanceof Map<?, ?>)
            return "{}";
        if (v instanceof List<?>)
            return "[]";
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long)
            return v.toString();
        if (v instanceof BigDecimal d)
            return d.toPlainString();
        if (v instanceof String s)
            return quote(s);
        throw new IllegalArgumentException("Unsupported YAML value: " + v.getClass().getName());
    }

    /** Double-quoted scalar. Anything outside YAML's printable set is escaped. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            switch (cp) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (printable(cp) && !(Character.charCount(cp) == 1 && Character.isSurrogate((char) cp))) {
                        b.appendCodePoint(cp);
                    } else {
                        b.append(String.format("\\u%04X", cp));
                    }
                }
            }
            i += Character.charCount(cp);
        }
        return b.append('"').toString();
    }

    /**
     * YAML 1.1 printable set (SnakeYAML), minus the line separators and BOM some
     * readers mangle.
     */
    private static boolean printable(int cp) {
        if (cp == 0x2028 || cp == 0x2029 || cp == 0xFEFF || cp == 0x85)
            return false;
        return (cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA0 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
    }
}
