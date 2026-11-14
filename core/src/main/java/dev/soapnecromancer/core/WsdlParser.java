package dev.soapnecromancer.core;

import dev.soapnecromancer.runtime.model.ElementRef;
import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import dev.soapnecromancer.runtime.model.SoapVersion;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.resolver.URIResolver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.wsdl.Import;
import javax.wsdl.Message;
import javax.wsdl.Operation;
import javax.wsdl.Part;
import javax.wsdl.Port;
import javax.wsdl.Service;
import javax.wsdl.WSDLException;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLLocator;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Generator entry point: WSDL 1.1 document/literal bytes (SOAP 1.1 or 1.2
 * binding) -> canonical
 * model + warnings. See .kiro/specs/parser/requirements.md.
 */
public final class WsdlParser {
    static final String WSDL11 = "http://schemas.xmlsoap.org/wsdl/";
    static final String XS = "http://www.w3.org/2001/XMLSchema";
    private static final Set<String> SECURITY_NAMESPACES = Set.of(
            "http://schemas.xmlsoap.org/ws/2004/09/policy",
            "http://www.w3.org/ns/ws-policy",
            "http://www.w3.org/2006/07/ws-policy",
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd",
            "http://schemas.xmlsoap.org/ws/2005/07/securitypolicy",
            "http://docs.oasis-open.org/ws-sx/ws-securitypolicy/200512",
            "http://docs.oasis-open.org/ws-sx/ws-securitypolicy/200702");
    /**
     * Requirement 2.17: the name becomes a URL path, an MCP tool name and a file
     * name.
     */
    private static final Pattern OPERATION_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String RPC = "rpc";
    private static final String ENCODED = "encoded";

    /**
     * Parser Requirement 5. XML depth is fixed in
     * {@link SecureXml#MAX_ELEMENT_DEPTH}.
     */
    public record Limits(int maxOperations, int maxTypes, int maxTypeDepth) {
        public static final Limits DEFAULT = new Limits(200, 1000, 64);
    }

    /**
     * The one port (or, without services, the one binding) the model is built from.
     */
    private record PortChoice(String serviceName, String portName, SoapBinding binding, String endpoint) {
        String describe() {
            return portName + " (SOAP " + binding.version().label() + ")";
        }
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
        try {
            return parse(doc, root, warnings);
        } catch (WsdlParseException e) {
            throw e;
        } catch (RuntimeException e) {
            // Requirement 1.6: WSDL4J and XmlSchema throw unchecked exceptions for many
            // malformed inputs.
            throw new WsdlParseException("The WSDL could not be processed: " + describe(e), warnings);
        }
    }

    private ParseResult parse(Document doc, Element root, List<Warning> warnings) {
        Definition def = readDefinition(doc);
        importWarnings(def, warnings);
        scanSecurity(root, warnings);

        XmlSchemaCollection schemas = readSchemas(root, warnings);
        SchemaWalker walker = new SchemaWalker(schemas, warnings, limits.maxTypes(), limits.maxTypeDepth());

        PortChoice choice = choosePort(def, root, warnings);
        List<OperationModel> operations = choice == null ? List.of() : operations(choice.binding(), walker, warnings);
        if (operations.isEmpty()) {
            throw new WsdlParseException(
                    "No supported operations: the generator needs a SOAP 1.1 or 1.2 document/literal binding",
                    warnings);
        }
        ServiceModel model = new ServiceModel(choice.serviceName(), def.getTargetNamespace(), choice.endpoint(),
                choice.binding().version(), operations, walker.types());
        checkRequiredCycles(model, warnings);
        return new ParseResult(model, warnings);
    }

    private static void importWarnings(Definition def, List<Warning> warnings) {
        for (Object imports : def.getImports().values()) {
            for (Object o : (List<?>) imports) {
                Import imp = (Import) o;
                warnings.add(new Warning("EXTERNAL_IMPORT", "wsdl:import is not fetched; its contents are ignored",
                        "import[" + imp.getLocationURI() + "]"));
            }
        }
    }

    // ------------------------------------------------------------------ port and
    // binding choice

    /**
     * Requirements 2.2, 2.10, 2.13-2.15. Services and ports are walked in document
     * order: WSDL4J
     * keeps them in hash maps, which would make "the first port" depend on the
     * names. The first
     * SOAP 1.1 port wins; without one, the first SOAP 1.2 port.
     */
    private static PortChoice choosePort(Definition def, Element root, List<Warning> warnings) {
        String tns = def.getTargetNamespace();
        List<PortChoice> candidates = new ArrayList<>();
        for (Element serviceEl : children(root, WSDL11, "service")) {
            String serviceName = serviceEl.getAttribute("name");
            Service service = serviceName.isEmpty() ? null : def.getService(new QName(tns, serviceName));
            for (Element portEl : children(serviceEl, WSDL11, "port")) {
                String portName = portEl.getAttribute("name");
                String loc = "service[" + serviceName + "]/port[" + portName + "]";
                Port port = service == null || portName.isEmpty() ? null : service.getPort(portName);
                Binding b = port == null ? null : port.getBinding();
                Optional<SoapBinding> soap = SoapBinding.of(b);
                if (soap.isPresent()) {
                    candidates.add(new PortChoice(serviceName, serviceName + "/" + portName, soap.get(),
                            soap.get().address(port)));
                } else if (b == null || b.isUndefined()) {
                    warnings.add(new Warning("UNSUPPORTED_BINDING",
                            "Port has no name or binds an undefined binding; skipped", loc));
                } else {
                    warnings.add(new Warning("UNSUPPORTED_BINDING",
                            "Port uses a non-SOAP binding (for example HTTP or MIME); skipped", loc));
                }
            }
        }
        if (candidates.isEmpty())
            return fallbackBinding(def, root, warnings);

        PortChoice chosen = preferSoap11(candidates);
        reportSkipped("ports", chosen, candidates, warnings);
        if (chosen.endpoint() == null || chosen.endpoint().isBlank()) {
            warnings.add(new Warning("NO_ADDRESS", "Port " + chosen.portName()
                    + " has no SOAP address; the endpoint URL must be configured", "service"));
            return new PortChoice(chosen.serviceName(), chosen.portName(), chosen.binding(), null);
        }
        return chosen;
    }

    /**
     * Requirement 2.15: no usable service; build from the first SOAP binding in
     * document order.
     */
    private static PortChoice fallbackBinding(Definition def, Element root, List<Warning> warnings) {
        String serviceName = def.getQName() != null ? def.getQName().getLocalPart() : "SoapService";
        List<PortChoice> candidates = new ArrayList<>();
        for (Element bindingEl : children(root, WSDL11, "binding")) {
            String name = bindingEl.getAttribute("name");
            Binding b = name.isEmpty() ? null : def.getBinding(new QName(def.getTargetNamespace(), name));
            SoapBinding.of(b).ifPresent(soap -> candidates.add(new PortChoice(serviceName, name, soap, null)));
        }
        if (candidates.isEmpty())
            return null;
        PortChoice chosen = preferSoap11(candidates);
        reportSkipped("bindings", chosen, candidates, warnings);
        warnings.add(new Warning("NO_ADDRESS",
                "No wsdl:service with a SOAP port; endpoint URL must be configured", "definitions"));
        return chosen;
    }

    private static PortChoice preferSoap11(List<PortChoice> candidates) {
        return candidates.stream().filter(c -> c.binding().version() == SoapVersion.SOAP_11).findFirst()
                .orElse(candidates.get(0));
    }

    private static void reportSkipped(String what, PortChoice chosen, List<PortChoice> candidates,
            List<Warning> warnings) {
        if (candidates.size() < 2)
            return;
        List<String> skipped = candidates.stream().filter(c -> c != chosen).map(PortChoice::describe).toList();
        warnings.add(new Warning("MULTIPLE_PORTS", "Several SOAP " + what + " found; using " + chosen.describe()
                + ", skipped " + String.join(", ", skipped), "definitions"));
    }

    // ------------------------------------------------------------------ operations

    private List<OperationModel> operations(SoapBinding soap, SchemaWalker walker, List<Warning> warnings) {
        List<?> bindingOperations = soap.binding().getBindingOperations();
        if (bindingOperations.size() > limits.maxOperations()) {
            warnings.add(new Warning("LIMIT_OPERATIONS", bindingOperations.size() + " operations; the limit is "
                    + limits.maxOperations(), "definitions"));
            throw new WsdlParseException("Too many operations (" + bindingOperations.size() + " > "
                    + limits.maxOperations() + "). Split the WSDL or generate a subset.", warnings);
        }
        String bindingName = local(soap.binding().getQName(), "binding");
        String style = soap.style();
        Map<String, OperationModel> byName = new LinkedHashMap<>();
        for (Object o : bindingOperations) {
            BindingOperation bop = (BindingOperation) o;
            String loc = "binding[" + bindingName + "]/operation[" + bop.getName() + "]";
            if (byName.containsKey(bop.getName())) {
                warnings.add(new Warning("DUPLICATE_OPERATION",
                        "Another operation has the same name (an overload); only the first is kept", loc));
                continue;
            }
            OperationModel op = operation(soap, bop, style, loc, walker, warnings);
            if (op != null)
                byName.put(op.name(), op);
        }
        return List.copyOf(byName.values());
    }

    private static OperationModel operation(SoapBinding soap, BindingOperation bop, String bindingStyle, String loc,
            SchemaWalker walker, List<Warning> warnings) {
        if (bop.getName() == null || !OPERATION_NAME.matcher(bop.getName()).matches()) {
            warnings.add(new Warning("UNSUPPORTED_NAME", "Operation names must match [A-Za-z_][A-Za-z0-9_.-]* "
                    + "and be at most 128 characters; operation skipped", loc));
            return null;
        }
        SoapBinding.OperationInfo info = soap.operation(bop);
        String style = info.style() != null ? info.style() : bindingStyle;
        if (RPC.equalsIgnoreCase(style)) {
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
        if (ENCODED.equalsIgnoreCase(soap.bodyUse(inExt)) || ENCODED.equalsIgnoreCase(soap.bodyUse(outExt))) {
            warnings.add(new Warning("ENCODED_USE", "use=\"encoded\" is not supported; operation skipped", loc));
            return null;
        }
        if (!soap.headers(inExt).isEmpty() || !soap.headers(outExt).isEmpty()) {
            warnings.add(new Warning("SOAP_HEADER", "soap:header parts are ignored", loc));
        }
        QName inEl = bodyElement(soap, op.getInput().getMessage(), inExt, loc + "/input", warnings);
        QName outEl = bodyElement(soap, op.getOutput().getMessage(), outExt, loc + "/output", warnings);
        if (inEl == null || outEl == null)
            return null;

        ElementRef input = walker.element(inEl, loc + "/input");
        ElementRef output = walker.element(outEl, loc + "/output");
        if (input == null || output == null)
            return null;

        return new OperationModel(bop.getName(), info.action(), documentation(op), input, output);
    }

    /**
     * Requirement 2.6: exactly one body part, bound to an element. Body parts are
     * those named by
     * {@code soap:body parts=}, or else every part not carried by a
     * {@code soap:header}.
     */
    private static QName bodyElement(SoapBinding soap, Message msg, List<?> bindingExt, String loc,
            List<Warning> warnings) {
        if (msg == null || msg.isUndefined()) {
            warnings.add(new Warning("UNSUPPORTED_MESSAGE", "Message is not defined; operation skipped", loc));
            return null;
        }
        List<Part> parts = bodyParts(soap, msg, bindingExt);
        String msgName = local(msg.getQName(), "message");
        if (parts.size() != 1) {
            warnings.add(new Warning("UNSUPPORTED_MESSAGE", "Message " + msgName + " has " + parts.size()
                    + " body parts; exactly one element part is required. Operation skipped", loc));
            return null;
        }
        Part part = parts.get(0);
        if (part.getElementName() == null) {
            warnings.add(new Warning("UNSUPPORTED_MESSAGE", "Part " + part.getName()
                    + " uses type= instead of element= (not document/literal wrapped). Operation skipped", loc));
            return null;
        }
        return part.getElementName();
    }

    private static List<Part> bodyParts(SoapBinding soap, Message msg, List<?> bindingExt) {
        Map<?, ?> all = msg.getParts();
        List<?> named = soap.bodyParts(bindingExt);
        List<Part> result = new ArrayList<>();
        if (named != null) {
            for (Object name : named) {
                if (all.get(name) instanceof Part p)
                    result.add(p);
            }
            return result;
        }
        Set<String> inHeaders = new HashSet<>();
        for (SoapBinding.Header h : soap.headers(bindingExt)) {
            if (Objects.equals(h.message(), msg.getQName()) && h.part() != null)
                inHeaders.add(h.part());
        }
        List<Part> ordered = new ArrayList<>();
        for (Object o : msg.getOrderedParts(null)) {
            if (o instanceof Part p)
                ordered.add(p);
        }
        for (Part p : ordered) {
            if (!inHeaders.contains(p.getName()))
                result.add(p);
        }
        // WSDL 1.1 (3.5): without parts=, every part is in the body. Excluding header
        // parts is the
        // usual intent, but when that leaves nothing, the header simply reuses the body
        // part.
        return result.isEmpty() ? ordered : result;
    }

    private static String documentation(Operation op) {
        Element doc = op.getDocumentationElement();
        return doc == null ? "" : WHITESPACE.matcher(doc.getTextContent().trim()).replaceAll(" ");
    }

    // ------------------------------------------------------------------
    // required-field cycles

    private static void checkRequiredCycles(ServiceModel model, List<Warning> warnings) {
        List<String> cycle = requiredCycle(model);
        if (cycle.isEmpty())
            return;
        warnings.add(new Warning("UNSATISFIABLE_RECURSION", "Required fields form a cycle "
                + String.join(" -> ", cycle) + "; no finite message exists", "type[" + cycle.get(0) + "]"));
        throw new WsdlParseException("Type " + cycle.get(0)
                + " can never be sent: its required fields contain itself (" + String.join(" -> ", cycle) + ")",
                warnings);
    }

    private enum Visit {
        ON_STACK, DONE
    }

    /**
     * A cycle of complex types through fields with minOccurs >= 1, as a path that
     * starts and ends at the same type, or empty. Optional recursion (a tree) is
     * fine and not
     * reported. Iterative, so a long chain of required fields cannot overflow the
     * stack.
     */
    static List<String> requiredCycle(ServiceModel model) {
        Map<String, Visit> state = new HashMap<>();
        for (String start : model.types().keySet()) {
            if (state.containsKey(start) || !model.type(start).isComplex())
                continue;
            // Each frame: a type id and an iterator over its remaining required complex
            // children.
            Deque<Map.Entry<String, Iterator<String>>> stack = new ArrayDeque<>();
            push(model, start, state, stack);
            while (!stack.isEmpty()) {
                var top = stack.peek();
                if (!top.getValue().hasNext()) {
                    state.put(top.getKey(), Visit.DONE);
                    stack.pop();
                    continue;
                }
                String next = top.getValue().next();
                Visit v = state.get(next);
                if (v == Visit.ON_STACK)
                    return cyclePath(stack, next);
                if (v == null)
                    push(model, next, state, stack);
            }
        }
        return List.of();
    }

    private static void push(ServiceModel model, String id, Map<String, Visit> state,
            Deque<Map.Entry<String, Iterator<String>>> stack) {
        state.put(id, Visit.ON_STACK);
        List<String> required = model.type(id).fields().stream()
                .filter(f -> f.minOccurs() >= 1 && model.type(f.typeId()).isComplex())
                .map(f -> f.typeId()).toList();
        stack.push(Map.entry(id, required.iterator()));
    }

    private static List<String> cyclePath(Deque<Map.Entry<String, Iterator<String>>> stack, String repeated) {
        List<String> path = new ArrayList<>();
        boolean in = false;
        for (Iterator<Map.Entry<String, Iterator<String>>> it = stack.descendingIterator(); it.hasNext();) {
            String s = it.next().getKey();
            if (s.equals(repeated))
                in = true;
            if (in)
                path.add(s);
        }
        path.add(repeated);
        return path;
    }

    // ------------------------------------------------------------------ reading

    /**
     * WSDL4J resolves xs:import/xs:include schemaLocation inside wsdl:types even
     * with importDocuments=false (SSRF), and it parses <em>every</em> wsdl:types
     * element. It only
     * needs part element QNames, so it gets a copy with all of them removed;
     * schemas are read
     * separately by {@link #readSchemas} with a no-fetch resolver. The locator
     * refuses any import
     * that would still be attempted.
     */
    private static Definition readDefinition(Document doc) {
        Document structureOnly = (Document) doc.cloneNode(true);
        Element root = structureOnly.getDocumentElement();
        for (Element types : children(root, WSDL11, "types"))
            root.removeChild(types);
        try {
            WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();
            reader.setFeature("javax.wsdl.verbose", false);
            reader.setFeature("javax.wsdl.importDocuments", false);
            return reader.readWSDL(new RefusingLocator(), root);
        } catch (WSDLException e) {
            throw new WsdlParseException("Invalid WSDL: " + e.getMessage(), e);
        }
    }

    /**
     * Never supplies a document: WSDL4J reports "unable to locate" instead of
     * fetching.
     */
    private static final class RefusingLocator implements WSDLLocator {
        @Override
        public InputSource getBaseInputSource() {
            return null;
        }

        @Override
        public InputSource getImportInputSource(String parentLocation, String importLocation) {
            return null;
        }

        @Override
        public String getBaseURI() {
            return "urn:soap-necromancer:upload";
        }

        @Override
        public String getLatestImportURI() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Reads every inline xs:schema, from every wsdl:types, into one collection.
     * External
     * locations are never fetched.
     */
    private static XmlSchemaCollection readSchemas(Element root, List<Warning> warnings) {
        XmlSchemaCollection collection = new XmlSchemaCollection();
        collection.setSchemaResolver(new NoFetchResolver(warnings));
        List<Element> schemaElements = new ArrayList<>();
        for (Element types : children(root, WSDL11, "types"))
            schemaElements.addAll(children(types, XS, "schema"));
        Set<String> namespaces = new LinkedHashSet<>();
        for (int i = 0; i < schemaElements.size(); i++) {
            // Unique system id per inline schema; sibling imports without schemaLocation
            // resolve via the collection.
            XmlSchema s;
            try {
                s = collection.read(schemaElements.get(i), "urn:soap-necromancer:inline:" + i);
            } catch (RuntimeException e) {
                throw new WsdlParseException("Invalid schema in wsdl:types: " + describe(e), warnings);
            }
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
        /**
         * A unique system id per unresolved location (hash codes collide: "Aa" and
         * "BB").
         */
        private int unresolved;

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
            empty.setSystemId("urn:soap-necromancer:unresolved:" + unresolved++);
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

    // ------------------------------------------------------------------ small
    // helpers

    /** WSDL4J leaves the QName null when the name attribute is missing. */
    private static String local(QName name, String fallback) {
        return name == null ? fallback : name.getLocalPart();
    }

    /**
     * The exception type plus a short message: enough to act on, never a stack
     * trace.
     */
    private static String describe(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().strip();
        if (msg.length() > 200)
            msg = msg.substring(0, 200) + "...";
        return e.getClass().getSimpleName() + (msg.isEmpty() ? "" : " (" + msg + ")");
    }

    /**
     * Element children with the given name, in document order. A copy: safe to
     * remove from.
     */
    private static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName()))
                out.add(e);
        }
        return out;
    }
}
