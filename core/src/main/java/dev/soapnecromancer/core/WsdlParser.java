package dev.soapnecromancer.core;

import dev.soapnecromancer.runtime.model.ElementRef;
import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.resolver.URIResolver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.wsdl.*;
import javax.wsdl.extensions.soap.SOAPAddress;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.wsdl.extensions.soap.SOAPBody;
import javax.wsdl.extensions.soap.SOAPHeader;
import javax.wsdl.extensions.soap.SOAPOperation;
import javax.wsdl.extensions.soap12.SOAP12Binding;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import java.io.StringReader;
import java.util.*;

/**
 * Generator entry point: WSDL 1.1 document/literal bytes -> canonical model +
 * warnings.
 * See .kiro/specs/parser/requirements.md.
 */
public final class WsdlParser {
    static final String WSDL11 = "http://schemas.xmlsoap.org/wsdl/";
    static final String XS = "http://www.w3.org/2001/XMLSchema";
    private static final Set<String> SECURITY_NAMESPACES = Set.of(
            "http://schemas.xmlsoap.org/ws/2004/09/policy",
            "http://www.w3.org/ns/ws-policy",
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd",
            "http://schemas.xmlsoap.org/ws/2005/07/securitypolicy",
            "http://docs.oasis-open.org/ws-sx/ws-securitypolicy/200702");

    /**
     * Parser Requirement 5. XML depth is fixed in
     * {@link SecureXml#MAX_ELEMENT_DEPTH}.
     */
    public record Limits(int maxOperations, int maxTypes, int maxTypeDepth) {
        public static final Limits DEFAULT = new Limits(200, 1000, 64);
    }

    private final Limits limits;

    public WsdlParser() {
        this(Limits.DEFAULT);
    }

    public WsdlParser(Limits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    public ParseResult parse(byte[] wsdl) {
        Document doc = SecureXml.parse(wsdl);
        Element root = doc.getDocumentElement();
        if (!WSDL11.equals(root.getNamespaceURI()) || !"definitions".equals(root.getLocalName())) {
            throw new WsdlParseException("Not a WSDL 1.1 document: root element is {" + root.getNamespaceURI() + "}"
                    + root.getLocalName() + " (WSDL 2.0 is not supported)", List.of());
        }

        List<Warning> warnings = new ArrayList<>();
        Definition def = readDefinition(doc);
        for (Object imports : def.getImports().values()) {
            for (Object o : (List<?>) imports) {
                Import imp = (Import) o;
                warnings.add(new Warning("EXTERNAL_IMPORT", "wsdl:import is not fetched; its contents are ignored",
                        "import[" + imp.getLocationURI() + "]"));
            }
        }
        scanSecurity(root, warnings);

        XmlSchemaCollection schemas = readSchemas(root, warnings);
        SchemaWalker walker = new SchemaWalker(schemas, warnings, limits.maxTypes(), limits.maxTypeDepth());

        List<OperationModel> operations = new ArrayList<>();
        List<Port> ports = soap11Ports(def, warnings);
        String endpoint = null;
        String serviceName;
        Collection<Binding> bindings;
        if (!ports.isEmpty()) {
            Port port = ports.get(0);
            endpoint = address(port);
            bindings = List.of(port.getBinding());
            serviceName = serviceNameOf(def, port);
        } else {
            bindings = soap11Bindings(def);
            if (!bindings.isEmpty()) {
                warnings.add(new Warning("NO_ADDRESS",
                        "No wsdl:service with a SOAP 1.1 port; endpoint URL must be configured", "definitions"));
            }
            serviceName = def.getQName() != null ? def.getQName().getLocalPart() : "SoapService";
        }

        int operationCount = 0;
        for (Binding binding : bindings)
            operationCount += binding.getBindingOperations().size();
        if (operationCount > limits.maxOperations()) {
            warnings.add(new Warning("LIMIT_OPERATIONS", operationCount + " operations; the limit is "
                    + limits.maxOperations(), "definitions"));
            throw new WsdlParseException("Too many operations (" + operationCount + " > " + limits.maxOperations()
                    + "). Split the WSDL or generate a subset.", warnings);
        }

        for (Binding binding : bindings) {
            String style = bindingStyle(binding);
            for (Object o : binding.getBindingOperations()) {
                OperationModel op = operation(binding, (BindingOperation) o, style, walker, warnings);
                if (op != null)
                    operations.add(op);
            }
        }

        if (operations.isEmpty()) {
            throw new WsdlParseException(
                    "No supported operations: the generator needs a SOAP 1.1 document/literal binding", warnings);
        }
        ServiceModel model = new ServiceModel(serviceName, def.getTargetNamespace(), endpoint, operations,
                walker.types());
        List<String> cycle = requiredCycle(model);
        if (!cycle.isEmpty()) {
            warnings.add(new Warning("UNSATISFIABLE_RECURSION", "Required fields form a cycle "
                    + String.join(" -> ", cycle) + "; no finite message exists", "type[" + cycle.get(0) + "]"));
            throw new WsdlParseException("Type " + cycle.get(0)
                    + " can never be sent: its required fields contain itself (" + String.join(" -> ", cycle) + ")",
                    warnings);
        }
        return new ParseResult(model, warnings);
    }

    /**
     * A cycle of complex types through fields with minOccurs >= 1, as a path that
     * starts and ends at
     * the same type, or empty. Optional recursion (a tree) is fine and not
     * reported.
     */
    static List<String> requiredCycle(ServiceModel model) {
        Map<String, Integer> state = new HashMap<>(); // 1 = on the DFS stack, 2 = done
        Deque<String> stack = new ArrayDeque<>();
        for (String id : model.types().keySet()) {
            List<String> c = requiredCycle(model, id, state, stack);
            if (!c.isEmpty())
                return c;
        }
        return List.of();
    }

    private static List<String> requiredCycle(ServiceModel model, String id, Map<String, Integer> state,
            Deque<String> stack) {
        var type = model.type(id);
        if (!type.isComplex() || state.getOrDefault(id, 0) == 2)
            return List.of();
        if (state.getOrDefault(id, 0) == 1) {
            List<String> path = new ArrayList<>();
            boolean in = false;
            for (Iterator<String> it = stack.descendingIterator(); it.hasNext();) {
                String s = it.next();
                if (s.equals(id))
                    in = true;
                if (in)
                    path.add(s);
            }
            path.add(id);
            return path;
        }
        state.put(id, 1);
        stack.push(id);
        for (var f : type.fields()) {
            if (f.minOccurs() >= 1) {
                List<String> c = requiredCycle(model, f.typeId(), state, stack);
                if (!c.isEmpty())
                    return c;
            }
        }
        stack.pop();
        state.put(id, 2);
        return List.of();
    }

    /**
     * WSDL4J resolves xs:import/xs:include schemaLocation inside wsdl:types even
     * with
     * importDocuments=false (SSRF). It only needs part element QNames, so it gets a
     * copy
     * without wsdl:types; schemas are read separately by {@link #readSchemas} with
     * a no-fetch resolver.
     */
    private static Definition readDefinition(Document doc) {
        try {
            Document structureOnly = (Document) doc.cloneNode(true);
            Element types = child(structureOnly.getDocumentElement(), WSDL11, "types");
            if (types != null)
                types.getParentNode().removeChild(types);

            WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();
            reader.setFeature("javax.wsdl.verbose", false);
            reader.setFeature("javax.wsdl.importDocuments", false);
            return reader.readWSDL(null, structureOnly);
        } catch (WSDLException e) {
            throw new WsdlParseException("Invalid WSDL: " + e.getMessage(), e);
        }
    }

    /**
     * Reads every inline xs:schema into one collection. External locations are
     * never fetched.
     */
    private static XmlSchemaCollection readSchemas(Element root, List<Warning> warnings) {
        XmlSchemaCollection collection = new XmlSchemaCollection();
        collection.setSchemaResolver(new NoFetchResolver(warnings));
        List<Element> schemaElements = new ArrayList<>();
        Element types = child(root, WSDL11, "types");
        if (types != null) {
            for (Node n = types.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && XS.equals(e.getNamespaceURI()) && "schema".equals(e.getLocalName())) {
                    schemaElements.add(e);
                }
            }
        }
        Set<String> namespaces = new LinkedHashSet<>();
        for (int i = 0; i < schemaElements.size(); i++) {
            // Unique system id per inline schema; sibling imports without schemaLocation
            // resolve via the collection.
            XmlSchema s = collection.read(schemaElements.get(i), "urn:soap-necromancer:inline:" + i);
            namespaces.add(s.getTargetNamespace() == null ? "" : s.getTargetNamespace());
        }
        if (namespaces.size() > 1) {
            warnings.add(new Warning("MULTIPLE_NAMESPACES", "Schemas use " + namespaces.size()
                    + " target namespaces; supported, but outside the tested core subset", "types"));
        }
        return collection;
    }

    private static final class NoFetchResolver implements URIResolver {
        private final List<Warning> warnings;

        NoFetchResolver(List<Warning> warnings) {
            this.warnings = warnings;
        }

        @Override
        public InputSource resolveEntity(String namespace, String schemaLocation, String baseUri) {
            if (schemaLocation == null || schemaLocation.isBlank())
                return null; // sibling inline schema
            warnings.add(new Warning("EXTERNAL_IMPORT",
                    "Schema import/include is not fetched; referenced types become string",
                    "schemaLocation[" + schemaLocation + "]"));
            String ns = namespace == null ? "" : " targetNamespace=\"" + escapeAttr(namespace) + "\"";
            InputSource empty = new InputSource(new StringReader("<xs:schema xmlns:xs=\"" + XS + "\"" + ns + "/>"));
            empty.setSystemId("urn:soap-necromancer:unresolved:" + Objects.hashCode(schemaLocation));
            return empty;
        }
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static void scanSecurity(Element root, List<Warning> warnings) {
        Deque<Element> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Element e = stack.pop();
            if (e.getNamespaceURI() != null && SECURITY_NAMESPACES.contains(e.getNamespaceURI())) {
                warnings.add(new Warning("WS_SECURITY",
                        "WS-Policy/WS-Security is not supported; requests are sent without security headers",
                        path(e)));
                return; // one warning is enough; the generated proxy cannot satisfy any policy
            }
            for (Node n = e.getLastChild(); n != null; n = n.getPreviousSibling()) {
                if (n instanceof Element c)
                    stack.push(c);
            }
        }
    }

    private static String path(Element e) {
        Deque<String> parts = new ArrayDeque<>();
        for (Node n = e; n instanceof Element el; n = n.getParentNode()) {
            String name = el.getAttribute("name");
            parts.push(el.getLocalName() + (name.isEmpty() ? "" : "[" + name + "]"));
        }
        return String.join("/", parts);
    }

    private static List<Port> soap11Ports(Definition def, List<Warning> warnings) {
        List<Port> result = new ArrayList<>();
        for (Object s : def.getAllServices().values()) {
            Service service = (Service) s;
            for (Object p : service.getPorts().values()) {
                Port port = (Port) p;
                Binding b = port.getBinding();
                if (b == null || b.isUndefined())
                    continue;
                if (isSoap12(b)) {
                    warnings.add(new Warning("SOAP12_BINDING", "SOAP 1.2 bindings are not supported and are skipped",
                            "service[" + service.getQName().getLocalPart() + "]/port[" + port.getName() + "]"));
                } else if (isSoap11(b)) {
                    result.add(port);
                }
            }
        }
        if (result.size() > 1) {
            Port first = result.get(0);
            warnings.add(new Warning("MULTIPLE_PORTS", "Several SOAP 1.1 ports found; using " + first.getName(),
                    "definitions"));
        }
        return result;
    }

    private static List<Binding> soap11Bindings(Definition def) {
        List<Binding> result = new ArrayList<>();
        for (Object b : def.getAllBindings().values()) {
            if (isSoap11((Binding) b))
                result.add((Binding) b);
        }
        return result;
    }

    private static String serviceNameOf(Definition def, Port port) {
        for (Object s : def.getAllServices().values()) {
            Service service = (Service) s;
            if (service.getPorts().containsValue(port))
                return service.getQName().getLocalPart();
        }
        return "SoapService";
    }

    private static boolean isSoap11(Binding b) {
        return ext(b.getExtensibilityElements(), SOAPBinding.class) != null;
    }

    private static boolean isSoap12(Binding b) {
        return ext(b.getExtensibilityElements(), SOAP12Binding.class) != null;
    }

    private static String bindingStyle(Binding b) {
        SOAPBinding sb = ext(b.getExtensibilityElements(), SOAPBinding.class);
        return sb == null || sb.getStyle() == null ? "document" : sb.getStyle();
    }

    private static String address(Port port) {
        SOAPAddress a = ext(port.getExtensibilityElements(), SOAPAddress.class);
        return a == null ? null : a.getLocationURI();
    }

    private static OperationModel operation(Binding binding, BindingOperation bop, String bindingStyle,
            SchemaWalker walker, List<Warning> warnings) {
        String loc = "binding[" + binding.getQName().getLocalPart() + "]/operation[" + bop.getName() + "]";
        SOAPOperation soapOp = ext(bop.getExtensibilityElements(), SOAPOperation.class);
        String style = soapOp != null && soapOp.getStyle() != null ? soapOp.getStyle() : bindingStyle;
        if ("rpc".equals(style)) {
            warnings.add(new Warning("RPC_STYLE", "rpc style is not supported; operation skipped", loc));
            return null;
        }
        Operation op = bop.getOperation();
        if (op == null || op.getInput() == null || bop.getBindingInput() == null) {
            warnings.add(new Warning("UNSUPPORTED_MESSAGE", "Operation has no input; skipped", loc));
            return null;
        }
        if (op.getOutput() == null || bop.getBindingOutput() == null) {
            warnings.add(
                    new Warning("ONE_WAY_OPERATION", "One-way operations are not supported; operation skipped", loc));
            return null;
        }
        List<?> inExt = bop.getBindingInput().getExtensibilityElements();
        List<?> outExt = bop.getBindingOutput().getExtensibilityElements();
        if (isEncoded(inExt) || isEncoded(outExt)) {
            warnings.add(new Warning("ENCODED_USE", "use=\"encoded\" is not supported; operation skipped", loc));
            return null;
        }
        if (ext(inExt, SOAPHeader.class) != null || ext(outExt, SOAPHeader.class) != null) {
            warnings.add(new Warning("SOAP_HEADER", "soap:header parts are ignored", loc));
        }
        QName inEl = singleElementPart(op.getInput().getMessage(), loc + "/input", warnings);
        QName outEl = singleElementPart(op.getOutput().getMessage(), loc + "/output", warnings);
        if (inEl == null || outEl == null)
            return null;

        ElementRef input = walker.element(inEl, loc + "/input");
        ElementRef output = walker.element(outEl, loc + "/output");
        if (input == null || output == null)
            return null;

        String action = soapOp == null ? "" : soapOp.getSoapActionURI();
        return new OperationModel(bop.getName(), action, documentation(op), input, output);
    }

    private static boolean isEncoded(List<?> extensibility) {
        SOAPBody body = ext(extensibility, SOAPBody.class);
        return body != null && "encoded".equals(body.getUse());
    }

    private static QName singleElementPart(Message msg, String loc, List<Warning> warnings) {
        if (msg == null || msg.isUndefined()) {
            warnings.add(new Warning("UNSUPPORTED_MESSAGE", "Message is not defined; operation skipped", loc));
            return null;
        }
        Map<?, ?> parts = msg.getParts();
        if (parts.size() != 1) {
            warnings.add(new Warning("UNSUPPORTED_MESSAGE", "Message " + msg.getQName().getLocalPart() + " has "
                    + parts.size() + " parts; exactly one element part is required. Operation skipped", loc));
            return null;
        }
        Part part = (Part) parts.values().iterator().next();
        if (part.getElementName() == null) {
            warnings.add(new Warning("UNSUPPORTED_MESSAGE", "Part " + part.getName()
                    + " uses type= instead of element= (not document/literal wrapped). Operation skipped", loc));
            return null;
        }
        return part.getElementName();
    }

    private static String documentation(Operation op) {
        Element doc = op.getDocumentationElement();
        return doc == null ? "" : doc.getTextContent().trim().replaceAll("\\s+", " ");
    }

    @SuppressWarnings("unchecked")
    private static <T> T ext(List<?> elements, Class<T> type) {
        if (elements == null)
            return null;
        for (Object o : elements) {
            if (type.isInstance(o))
                return (T) o;
        }
        return null;
    }

    private static Element child(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName()))
                return e;
        }
        return null;
    }
}
