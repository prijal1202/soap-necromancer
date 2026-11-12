package dev.soapnecromancer.generators;

/**
 * Escaping for model strings (from untrusted WSDLs) placed into generated
 * source files.
 * Templates never interpolate raw model text into code; they get one of these.
 */
public final class Escapes {
    private Escapes() {
    }

    /**
     * A complete Java string literal, quotes included. Non-ASCII is escaped so
     * encoding never matters.
     */
    public static String javaString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E)
                        b.append(String.format("\\u%04x", (int) c));
                    else
                        b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /**
     * Text safe inside a Java comment, on one line. Neutralizes the comment
     * terminator, and the
     * backslash: javac processes unicode escapes even inside comments, so a WSDL
     * containing a
     * backslash-u escape for a newline or for "*" followed by "/" could otherwise
     * inject code.
     * (Writing such an escape literally in this comment broke this very file's
     * compilation.)
     */
    public static String javaComment(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\\')
                b.append("&#92;");
            else if (c == '\r' || c == '\n' || c == '\t')
                b.append(' ');
            else if (c < 0x20)
                b.append('?');
            else
                b.append(c);
        }
        return b.toString().replace("*/", "*&#47;").replace("/*", "&#47;*");
    }

    /** Text for an XML comment or element content. */
    public static String xmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("--", "- -");
    }

    /** Text on one Markdown line; backticks would break inline code spans. */
    public static String markdown(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').replace('`', '\'');
    }
}
