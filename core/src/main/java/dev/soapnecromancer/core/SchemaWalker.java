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
            warn("XSD_NILLABLE", "nillable is ignored; xsi:nil is not sent or read", location);
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
                fields.add(field(ownerId, el, location));
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
                return new FieldModel(ref.getLocalPart(), namespace, builtIn(BuiltIn.STRING),
                        (int) el.getMinOccurs(), maxOccurs(el));
            }
        } else {
            namespace = qualified(el) ? el.getParent().getTargetNamespace() : "";
        }
        String name = target.getName();
        String fieldLoc = location + "/element[" + name + "]";
        String typeId = elementType(target, ownerId + capitalize(name), fieldLoc);
        return new FieldModel(name, namespace, typeId, (int) el.getMinOccurs(), maxOccurs(el));
    }

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
        if (p instanceof XmlSchemaChoice)
            warn("XSD_CHOICE", "xs:choice is not supported; its elements are dropped", location + "/choice");
        else if (p instanceof XmlSchemaAll)
            warn("XSD_ALL", "xs:all is not supported; its elements are dropped", location + "/all");
        else if (p instanceof XmlSchemaAny)
            warn("XSD_ANY", "xs:any is not supported and is dropped", location + "/any");
        else if (p instanceof XmlSchemaGroupRef)
            warn("XSD_GROUP", "xs:group references are not supported and are dropped", location + "/group");
        else
            warn("XSD_GROUP", "Unsupported particle " + p.getClass().getSimpleName() + " dropped", location);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void warn(String code, String message, String location) {
        warnings.add(new Warning(code, message, location));
    }
}
