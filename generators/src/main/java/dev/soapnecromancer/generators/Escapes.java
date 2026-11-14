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

    /** Text for XML element content. */
    public static String xmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Text for an XML comment: no {@code --} may remain (one replace pass leaves
     * {@code ---} as
     * {@code - --}), and a trailing {@code -} would join the closing {@code -->}.
     */
    public static String xmlComment(String s) {
        String out = xmlText(s);
        while (out.contains("--"))
            out = out.replace("--", "- -");
        return out.endsWith("-") ? out + " " : out;
    }

    /**
     * Text for a pom.xml value. Maven interpolates {@code ${...}} in model values
     * after XML
     * decoding, so a WSDL name like {@code ${env.SECRET}} would be expanded at
     * build time.
     */
    public static String pomText(String s) {
        return xmlText(s.replace("$", "").replace("{", "(").replace("}", ")"));
    }

    /**
     * Text on one Markdown line. Backticks would break inline code spans; brackets,
     * angle
     * brackets, {@code !} and the like would let WSDL text become links, images or
     * raw HTML in a
     * Markdown renderer. Those characters are backslash-escaped.
     */
    public static String markdown(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\r', '\n', '\t' -> b.append(' ');
                case '`' -> b.append('\'');
                case '\\', '*', '_', '[', ']', '<', '>', '!', '|', '&' -> b.append('\\').append(c);
                default -> {
                    if (c >= 0x20 && !isBidiControl(c))
                        b.append(c);
                }
            }
        }
        return b.toString();
    }

    /**
     * Text inside a Markdown inline code span: rendered literally, so only a
     * backtick (which would
     * end the span) and line breaks need handling. Backslash escapes would show up
     * verbatim here.
     */
    public static String markdownCode(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '`')
                b.append('\'');
            else if (c < 0x20)
                b.append(' ');
            else if (!isBidiControl(c))
                b.append(c);
        }
        return b.toString();
    }

    /**
     * Text an AI agent reads as tool documentation. The WSDL author controls it, so
     * it is
     * bounded, flattened to one line, and stripped of control and bidi characters
     * that can hide
     * instructions from a human reviewer.
     */
    public static String untrustedProse(String s, int maxLength) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), maxLength));
        boolean space = false;
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                space = b.length() > 0;
                continue;
            }
            if (isBidiControl(c) || Character.getType(c) == Character.FORMAT)
                continue;
            if (space) {
                b.append(' ');
                space = false;
            }
            b.append(c);
            if (b.length() >= maxLength) {
                b.setLength(maxLength);
                b.append("...");
                break;
            }
        }
        return b.toString();
    }

    private static boolean isBidiControl(char c) {
        return (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069') || c == '\u200E' || c == '\u200F'
                || c == '\u061C';
    }
}
