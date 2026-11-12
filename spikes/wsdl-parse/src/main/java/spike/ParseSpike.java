package spike;

import org.apache.ws.commons.schema.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.wsdl.*;
import javax.wsdl.extensions.ExtensibilityElement;
import javax.wsdl.extensions.schema.Schema;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Spike: hardened DOM -> WSDL4J -> inline schema -> XmlSchema walk. Also checks XXE is rejected. */
public class ParseSpike {
    public static void main(String[] args) throws Exception {
        try (InputStream in = ParseSpike.class.getResourceAsStream("/hello.wsdl")) {
            walk(parseHardened(in));
        }

        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]>"
                + "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\">&x;</wsdl:definitions>";
        try {
            parseHardened(new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8)));
            System.out.println("XXE: FAIL (document with DOCTYPE was accepted)");
        } catch (Exception e) {
            System.out.println("XXE: PASS (rejected: " + e.getClass().getSimpleName() + ")");
        }
    }

    static Document parseHardened(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(in);
    }

    static void walk(Document doc) throws Exception {
        WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();
        reader.setFeature("javax.wsdl.verbose", false);
        reader.setFeature("javax.wsdl.importDocuments", false);
        Definition def = reader.readWSDL(null, doc);
        System.out.println("targetNamespace = " + def.getTargetNamespace());

        for (Object b : def.getAllBindings().values()) {
            Binding binding = (Binding) b;
            for (Object ext : binding.getExtensibilityElements()) {
                if (ext instanceof SOAPBinding sb) System.out.println("binding style = " + sb.getStyle());
            }
            for (Object o : binding.getBindingOperations()) {
                BindingOperation op = (BindingOperation) o;
                Part part = (Part) op.getOperation().getInput().getMessage().getParts().values().iterator().next();
                System.out.println("operation " + op.getName() + " input element = " + part.getElementName());
            }
        }

        XmlSchemaCollection collection = new XmlSchemaCollection();
        for (Object ext : def.getTypes().getExtensibilityElements()) {
            if (ext instanceof Schema s) {
                Element schemaEl = s.getElement();
                XmlSchema schema = collection.read(schemaEl);
                for (XmlSchemaElement el : schema.getElements().values()) {
                    System.out.println("element " + el.getName());
                    var ct = (XmlSchemaComplexType) el.getSchemaType();
                    var seq = (XmlSchemaSequence) ct.getParticle();
                    for (XmlSchemaSequenceMember m : seq.getItems()) {
                        var child = (XmlSchemaElement) m;
                        System.out.printf("  %s : %s [%d..%s]%n", child.getName(), child.getSchemaTypeName(),
                                child.getMinOccurs(), child.getMaxOccurs() == Long.MAX_VALUE ? "*" : child.getMaxOccurs());
                    }
                }
            }
        }
    }
}
