package dev.soapnecromancer.runtime;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** XML helpers for the runtime: hardened parsing and text escaping. JDK only. */
public final class RuntimeXml {
    private RuntimeXml() {}

    public static Document parse(byte[] xml) throws SAXException {
        try {
            return newBuilder().parse(new ByteArrayInputStream(xml));
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }

    public static Document parse(String xml) throws SAXException {
        return parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    /** DOCTYPE disallowed, no external entities, DTDs, schemas or XInclude. */
    public static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) {}
                public void error(SAXParseException e) throws SAXException { throw e; }
                public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Appends {@code text} escaped for element content or attribute values. Carriage return is
     * escaped because parsers normalize a literal CR to LF. Returns the index of the first code
     * point not allowed by XML 1.0, or -1.
     */
    public static int appendEscaped(StringBuilder out, String text, boolean attribute) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!isXmlChar(cp) || (Character.isSurrogate(text.charAt(i)) && Character.charCount(cp) == 1)) {
                return i;
            }
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\r' -> out.append("&#13;");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                case '\n' -> out.append(attribute ? "&#10;" : "\n");
                case '\t' -> out.append(attribute ? "&#9;" : "\t");
                default -> out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return -1;
    }

    /** XML 1.0 Char production. */
    static boolean isXmlChar(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }
}
