package dev.soapnecromancer.core;

import dev.soapnecromancer.runtime.model.BuiltIn;
import dev.soapnecromancer.runtime.model.ElementRef;
import dev.soapnecromancer.runtime.model.FieldModel;
import dev.soapnecromancer.runtime.model.TypeModel;
import org.apache.ws.commons.schema.*;
import org.apache.ws.commons.schema.constants.Constants;

import javax.xml.namespace.QName;
import java.util.*;

/**
 * Maps the XSD core subset onto {@link TypeModel}s. Unsupported constructs are
 * dropped with a
 * warning; the rest of the type is kept. Requirement 3 of the parser spec.
 */
final class SchemaWalker {
    private static final String XS = Constants.URI_2001_SCHEMA_XSD;
    private static final Map<String, BuiltIn> BUILT_INS = Map.of(
            "string", BuiltIn.STRING, "normalizedString", BuiltIn.STRING, "token", BuiltIn.STRING,
            "int", BuiltIn.INT, "long", BuiltIn.LONG, "decimal", BuiltIn.DECIMAL,
            "boolean", BuiltIn.BOOLEAN, "date", BuiltIn.DATE, "dateTime", BuiltIn.DATE_TIME);

    private final XmlSchemaCollection collection;
    private final List<Warning> warnings;
    private final Map<String, TypeModel> types = new LinkedHashMap<>();
    private final Map<XmlSchemaType, String> resolved = new IdentityHashMap<>();
    private final Set<String> warnedBuiltIns = new HashSet<>();
    private final int maxTypes;
    private final int maxDepth;
    private int depth;

    SchemaWalker(XmlSchemaCollection collection, List<Warning> warnings, int maxTypes, int maxDepth) {
        this.collection = collection;
        this.warnings = warnings;
        this.maxTypes = maxTypes;
        this.maxDepth = maxDepth;
    }

    Map<String, TypeModel> types() {
        return types;
    }

    /**
     * Resolves a top-level element (a message part) or returns null with a warning.
     */
    ElementRef element(QName name, String location) {
        XmlSchemaElement el = collection.getElementByQName(name);
        if (el == null) {
            warn("UNRESOLVED_TYPE", "Element " + name + " is not defined in the WSDL's schemas", location);
            return null;
        }
        String typeId = elementType(el, el.getName(), location + "/element[" + el.getName() + "]");
        return new ElementRef(name.getNamespaceURI(), name.getLocalPart(), typeId);
    }

    private String elementType(XmlSchemaElement el, String anonymousId, String location) {
        if (el.isNillable()) {
            warn("XSD_NILLABLE",
                    "nillable: an xsi:nil element in a response is read as absent; requests never send xsi:nil",
                    location);
        }
        XmlSchemaType anonymous = el.getSchemaTypeName() == null ? el.getSchemaType() : null;
        if (anonymous != null)
            return type(anonymous, anonymousId, location);
        QName typeName = el.getSchemaTypeName();
        if (typeName == null) {
            // <xs:element name="x"/> with no type is xs:anyType.
            warn("UNSUPPORTED_BUILTIN", "Element without a type (anyType) is mapped to string", location);
            return builtIn(BuiltIn.STRING);
        }
        return named(typeName, location);
    }

    private String named(QName typeName, String location) {
        if (XS.equals(typeName.getNamespaceURI())) {
            BuiltIn b = BUILT_INS.get(typeName.getLocalPart());
            if (b == null) {
                if (warnedBuiltIns.add(typeName.getLocalPart())) {
                    warn("UNSUPPORTED_BUILTIN", "xs:" + typeName.getLocalPart() + " is mapped to string", location);
                }
                b = BuiltIn.STRING;
            }
            return builtIn(b);
        }
        XmlSchemaType t = collection.getTypeByQName(typeName);
        if (t == null) {
            warn("UNRESOLVED_TYPE", "Type " + typeName + " is not defined; mapped to string", location);
            return builtIn(BuiltIn.STRING);
        }
        return type(t, typeName.getLocalPart(), location);
    }

    private String builtIn(BuiltIn b) {
        types.putIfAbsent(b.typeId(), TypeModel.builtIn(b));
        return b.typeId();
    }

    private String type(XmlSchemaType t, String preferredId, String location) {
        String existing = resolved.get(t);
        if (existing != null)
            return existing;
        String id = uniqueId(t.getName() != null ? t.getName() : preferredId);
        // Parser Requirement 5.3/5.4: checked during the walk so a long chain of named
        // types
        // cannot overflow the stack (each level is several frames deep).
        if (types.size() >= maxTypes) {
            limit("LIMIT_TYPES", "More than " + maxTypes + " types are reachable from the operations", location);
        }
        if (depth >= maxDepth) {
            limit("LIMIT_DEPTH", "Types nest or reference each other more than " + maxDepth + " levels deep", location);
        }
        resolved.put(t, id);
        // Reserve the slot before recursing so recursive types terminate and keep a
        // stable order.
        types.put(id, null);
        String loc = location + "/type[" + id + "]";
        depth++;
        try {
            TypeModel model = t instanceof XmlSchemaComplexType ct ? complex(id, ct, loc)
                    : simple(id, (XmlSchemaSimpleType) t, loc);
            types.put(id, model);
        } finally {
            depth--;
        }
        return id;
    }

    private void limit(String code, String message, String location) {
        warnings.add(new Warning(code, message, location));
        throw new WsdlParseException(message + ". The generator stops here to protect the service.", warnings);
    }

    private String uniqueId(String base) {
        String id = base;
        for (int i = 2; types.containsKey(id); i++)
            id = base + i;
        return id;
    }

    private TypeModel simple(String id, XmlSchemaSimpleType st, String location) {
        XmlSchemaSimpleTypeContent content = st.getContent();
        if (!(content instanceof XmlSchemaSimpleTypeRestriction r)) {
            warn("XSD_LIST_UNION", "xs:list and xs:union are mapped to string", location);
            return TypeModel.simple(id, BuiltIn.STRING, List.of());
        }
        BuiltIn base = baseBuiltIn(r, location);
        List<String> values = new ArrayList<>();
        for (XmlSchemaFacet f : r.getFacets()) {
            if (f instanceof XmlSchemaEnumerationFacet e)
                values.add(String.valueOf(e.getValue()));
        }
        return TypeModel.simple(id, base, values);
    }

    private BuiltIn baseBuiltIn(XmlSchemaSimpleTypeRestriction r, String location) {
        QName baseName = r.getBaseTypeName();
        if (baseName == null)
            return BuiltIn.STRING;
        String baseId = named(baseName, location);
        TypeModel base = types.get(baseId);
        return base == null || base.base() == null ? BuiltIn.STRING : base.base();
    }

    private TypeModel complex(String id, XmlSchemaComplexType ct, String location) {
        if (!ct.getAttributes().isEmpty() || ct.getAnyAttribute() != null) {
            warn("XSD_ATTRIBUTE", "Attributes are ignored", location);
        }
        if (ct.getContentModel() != null) {
            warn("XSD_DERIVATION",
                    "complexContent/simpleContent (extension, restriction) is not supported; content dropped",
                    location);
            return TypeModel.complex(id, List.of());
        }
        if (ct.isMixed()) {
            warn("XSD_DERIVATION", "Mixed content is not supported; text content is ignored", location);
        }
        List<FieldModel> fields = new ArrayList<>();
        XmlSchemaParticle particle = ct.getParticle();
        if (particle instanceof XmlSchemaSequence seq) {
            sequence(id, seq, fields, location);
        } else if (particle != null) {
            unsupportedParticle(particle, location);
        }
        // Field names are JSON property names, so they must be unique within a type.
        Set<String> seen = new HashSet<>();
        List<FieldModel> unique = new ArrayList<>();
        for (FieldModel f : fields) {
            if (seen.add(f.name())) {
                unique.add(f);
            } else {
                warn("XSD_DUPLICATE_FIELD",
                        "Repeated element name '" + f.name() + "' in one sequence; later occurrence dropped", location);
            }
        }
        return TypeModel.complex(id, unique);
    }

    private void sequence(String ownerId, XmlSchemaSequence seq, List<FieldModel> fields, String location) {
        if (seq.getMinOccurs() != 1 || seq.getMaxOccurs() != 1) {
            warn("XSD_GROUP", "Occurrence constraints on xs:sequence are ignored", location + "/sequence");
        }
        for (XmlSchemaSequenceMember m : seq.getItems()) {
            if (m instanceof XmlSchemaElement el) {
                FieldModel f = field(ownerId, el, location);
                if (f != null)
                    fields.add(f);
            } else if (m instanceof XmlSchemaSequence nested) {
                sequence(ownerId, nested, fields, location);
            } else {
                unsupportedParticle((XmlSchemaObject) m, location);
            }
        }
    }

    private FieldModel field(String ownerId, XmlSchemaElement el, String location) {
        XmlSchemaElement target = el;
        String namespace;
        if (el.getRef().getTargetQName() != null) {
            QName ref = el.getRef().getTargetQName();
            target = collection.getElementByQName(ref);
            namespace = ref.getNamespaceURI();
            if (target == null) {
                warn("UNRESOLVED_TYPE", "Element ref " + ref + " is not defined; mapped to string", location);
                return occurs(new FieldModel(ref.getLocalPart(), namespace, builtIn(BuiltIn.STRING), 0, 0), el,
                        location);
            }
        } else {
            namespace = qualified(el) ? el.getParent().getTargetNamespace() : "";
        }
        String name = target.getName();
        if (name == null || !NCNAME.matcher(name).matches()) {
            warn("XSD_INVALID_NAME", "Element name is not a valid XML name; field dropped", location);
            return null;
        }
        String fieldLoc = location + "/element[" + name + "]";
        String typeId = elementType(target, ownerId + capitalize(name), fieldLoc);
        return occurs(new FieldModel(name, namespace, typeId, 0, 0), el, fieldLoc);
    }

    /**
     * Requirement 3.13. XmlSchema reports occurrences as longs; values past int
     * range, a minimum
     * above the maximum, or a huge minimum (generated tests would build that many
     * items) are
     * clamped with a warning.
     */
    private FieldModel occurs(FieldModel f, XmlSchemaElement el, String location) {
        int max = maxOccurs(el);
        if (max == 0) {
            warn("XSD_OCCURS", "maxOccurs=0 prohibits the element; field dropped", location);
            return null;
        }
        long rawMin = el.getMinOccurs();
        long bound = max == FieldModel.UNBOUNDED ? MAX_MIN_OCCURS : Math.min(max, MAX_MIN_OCCURS);
        int min = (int) Math.max(0, Math.min(rawMin, bound));
        if (min != rawMin) {
            warn("XSD_OCCURS", "minOccurs=" + rawMin + " is outside 0.." + bound + "; using " + min, location);
        }
        return new FieldModel(f.name(), f.namespace(), f.typeId(), min, max);
    }

    private static final int MAX_MIN_OCCURS = 1000;
    /**
     * XML NCName, approximated by Unicode classes: what an element local name may
     * be.
     */
    private static final java.util.regex.Pattern NCNAME = java.util.regex.Pattern
            .compile("[\\p{L}_][\\p{L}\\p{N}\\p{M}._\\-\\u00B7]*");

    private static boolean qualified(XmlSchemaElement el) {
        XmlSchemaForm form = el.getForm();
        if (form == XmlSchemaForm.QUALIFIED)
            return true;
        if (form == XmlSchemaForm.UNQUALIFIED)
            return false;
        return el.getParent().getElementFormDefault() == XmlSchemaForm.QUALIFIED;
    }

    private static int maxOccurs(XmlSchemaElement el) {
        long max = el.getMaxOccurs();
        return max == Long.MAX_VALUE || max > Integer.MAX_VALUE ? FieldModel.UNBOUNDED : (int) max;
    }

    private void unsupportedParticle(XmlSchemaObject p, String location) {
        switch (p) {
            case XmlSchemaChoice c ->
                warn("XSD_CHOICE", "xs:choice is not supported; its elements are dropped", location + "/choice");
            case XmlSchemaAll a ->
                warn("XSD_ALL", "xs:all is not supported; its elements are dropped", location + "/all");
            case XmlSchemaAny a -> warn("XSD_ANY", "xs:any is not supported and is dropped", location + "/any");
            case XmlSchemaGroupRef g ->
                warn("XSD_GROUP", "xs:group references are not supported and are dropped", location + "/group");
            default -> warn("XSD_GROUP", "Unsupported particle " + p.getClass().getSimpleName() + " dropped", location);
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void warn(String code, String message, String location) {
        warnings.add(new Warning(code, message, location));
    }
}
