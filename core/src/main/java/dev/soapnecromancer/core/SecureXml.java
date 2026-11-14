package dev.soapnecromancer.core;

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

/**
 * Hardened DOM parsing for untrusted uploads: no DOCTYPE, no external entities,
 * DTDs or schemas.
 */
public final class SecureXml {
    public static final int MAX_ELEMENT_DEPTH = 256;
    static final String MAX_ELEMENT_DEPTH_PROPERTY = "http://www.oracle.com/xml/jaxp/properties/maxElementDepth";

    private SecureXml() {
    }

    public static Document parse(byte[] xml) {
        try {
            return newBuilder().parse(new ByteArrayInputStream(xml));
        } catch (SAXParseException e) {
            throw new WsdlParseException("Invalid XML at line " + e.getLineNumber() + ": " + e.getMessage(), e);
        } catch (SAXException | IOException e) {
            throw new WsdlParseException("Invalid XML: " + e.getMessage(), e);
        }
    }

    public static DocumentBuilder newBuilder() {
        try {
            // The JDK's own parser: the maxElementDepth property below is JDK-specific, and
            // another
            // JAXP provider on the classpath could otherwise reject or ignore these
            // settings.
            DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // Parser Requirement 5.1: XmlSchema reads recursively, so deep nesting would
            // overflow the stack.
            f.setAttribute(MAX_ELEMENT_DEPTH_PROPERTY, String.valueOf(MAX_ELEMENT_DEPTH));
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) {
                }

                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }
}
