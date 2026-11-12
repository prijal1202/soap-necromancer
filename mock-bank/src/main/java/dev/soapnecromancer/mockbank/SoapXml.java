package dev.soapnecromancer.mockbank;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;

/**
 * Minimal SOAP 1.1 envelope handling on the JDK DOM. Kept separate from the
 * generator on purpose.
 */
final class SoapXml {
    static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String BANK_NS = "urn:example:bank:v1";

    private SoapXml() {
    }

    static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            // Rethrow instead of letting the JDK default handler print "[Fatal Error]" to
            // stderr.
            b.setErrorHandler(new org.xml.sax.ErrorHandler() {
                public void warning(org.xml.sax.SAXParseException e) {
                }

                public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }

                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }
            });
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the first element child of soap:Body. */
    static Element bodyPayload(byte[] envelope) {
        Document doc;
        try {
            doc = newBuilder().parse(new ByteArrayInputStream(envelope));
        } catch (Exception e) {
            throw new MockBankFault("soap:Client", "Malformed SOAP envelope");
        }
        Element env = doc.getDocumentElement();
        if (!SOAP_ENV.equals(env.getNamespaceURI()) || !"Envelope".equals(env.getLocalName())) {
            throw new MockBankFault("soap:VersionMismatch", "Expected a SOAP 1.1 Envelope");
        }
        Element body = firstChild(env, SOAP_ENV, "Body");
        if (body == null)
            throw new MockBankFault("soap:Client", "Missing soap:Body");
        for (Node n = body.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e)
                return e;
        }
        throw new MockBankFault("soap:Client", "Empty soap:Body");
    }

    static Element firstChild(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName()))
                return e;
        }
        return null;
    }

    static String text(Element parent, String local, boolean required) {
        Element e = firstChild(parent, BANK_NS, local);
        if (e == null) {
            if (required)
                throw new MockBankFault("soap:Client", "Missing element: " + local);
            return null;
        }
        return e.getTextContent().trim();
    }

    /** New envelope document; returns the soap:Body element to append into. */
    static Element newEnvelopeBody(Document doc) {
        Element env = doc.createElementNS(SOAP_ENV, "soap:Envelope");
        doc.appendChild(env);
        Element body = doc.createElementNS(SOAP_ENV, "soap:Body");
        env.appendChild(body);
        return body;
    }

    static Element add(Element parent, String local, Object value) {
        Element e = parent.getOwnerDocument().createElementNS(BANK_NS, "b:" + local);
        if (value != null)
            e.setTextContent(value.toString());
        parent.appendChild(e);
        return e;
    }

    static String fault(String code, String message) {
        Document doc = newBuilder().newDocument();
        Element body = newEnvelopeBody(doc);
        Element fault = doc.createElementNS(SOAP_ENV, "soap:Fault");
        body.appendChild(fault);
        Element fc = doc.createElement("faultcode");
        fc.setTextContent(code);
        fault.appendChild(fc);
        Element fs = doc.createElement("faultstring");
        fs.setTextContent(message);
        fault.appendChild(fs);
        return serialize(doc);
    }

    static String serialize(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
