package dev.soapnecromancer.runtime;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * XML helpers for the runtime: hardened parsing and text escaping. JDK only.
 */
public final class RuntimeXml {
    /**
     * Converter Requirement 2.8: deeper documents are rejected by the parser
     * itself.
     */
    public static final int MAX_ELEMENT_DEPTH = 256;
    private static final String MAX_ELEMENT_DEPTH_PROPERTY = "http://www.oracle.com/xml/jaxp/properties/maxElementDepth";

    /** Configured once; a factory is safe to share, a DocumentBuilder is not. */
    private static final DocumentBuilderFactory FACTORY = hardenedFactory();
    private static final ErrorHandler RETHROW = new ErrorHandler() {
        public void warning(SAXParseException e) {
        }

        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private RuntimeXml() {
    }

    public static Document parse(byte[] xml) throws SAXException {
        return parse(xml, null);
    }

    /**
     * Parses with the given charset when one is known from the transport (a
     * {@code Content-Type}
     * charset overrides the XML declaration, RFC 7303); otherwise the parser
     * detects it.
     */
    public static Document parse(byte[] xml, Charset charset) throws SAXException {
        InputSource source = new InputSource(new ByteArrayInputStream(xml));
        if (charset != null)
            source.setEncoding(charset.name());
        try {
            return newBuilder().parse(source);
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }

    public static Document parse(String xml) throws SAXException {
        return parse(xml.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /**
     * DOCTYPE disallowed, no external entities, DTDs, schemas or XInclude, depth
     * capped.
     */
    public static DocumentBuilder newBuilder() {
        try {
            DocumentBuilder b = FACTORY.newDocumentBuilder();
            b.setErrorHandler(RETHROW);
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code newDefaultInstance} pins the JDK parser: a JAXP provider on the
     * generated proxy's
     * classpath (an old Xerces, say) could otherwise ignore or reject these
     * settings.
     */
    private static DocumentBuilderFactory hardenedFactory() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setAttribute(MAX_ELEMENT_DEPTH_PROPERTY, String.valueOf(MAX_ELEMENT_DEPTH));
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Appends {@code text} escaped for element content or attribute values.
     * Carriage return is
     * escaped because parsers normalize a literal CR to LF. Returns the index of
     * the first code
     * point not allowed by XML 1.0, or -1. A lone surrogate decodes to its own code
     * point, which
     * {@link #isXmlChar} rejects.
     */
    public static int appendEscaped(StringBuilder out, String text, boolean attribute) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (!isXmlChar(cp)) {
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
