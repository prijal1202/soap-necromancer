package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.FieldModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import dev.soapnecromancer.runtime.model.TypeModel;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.List;
import java.util.Map;

/** Shared checks for converter Requirement 3.3 (exported in the runtime test-jar). */
public final class RoundTripAssertions {
    private RoundTripAssertions() {}

    /**
     * Walks a canonical value and its XML element together. Fails if an optional single-valued field
     * absent from the value has an element in the XML.
     */
    public static void assertAbsentOptionalsHaveNoElement(ServiceModel model, String typeId, Object value, Element el) {
        TypeModel t = model.type(typeId);
        if (!t.isComplex()) return;
        Map<?, ?> obj = (Map<?, ?>) value;
        for (FieldModel f : t.fields()) {
            List<Element> matches = children(el, f);
            Object v = obj.get(f.name());
            if (v == null) {
                if (!matches.isEmpty()) {
                    throw new AssertionError("absent field '" + f.name() + "' produced " + matches.size() + " element(s)");
                }
            } else if (f.repeated()) {
                List<?> items = (List<?>) v;
                for (int i = 0; i < items.size(); i++) {
                    assertAbsentOptionalsHaveNoElement(model, f.typeId(), items.get(i), matches.get(i));
                }
            } else {
                assertAbsentOptionalsHaveNoElement(model, f.typeId(), v, matches.get(0));
            }
        }
    }

    private static List<Element> children(Element parent, FieldModel f) {
        java.util.ArrayList<Element> out = new java.util.ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && f.name().equals(e.getLocalName())
                    && f.namespace().equals(e.getNamespaceURI() == null ? "" : e.getNamespaceURI())) {
                out.add(e);
            }
        }
        return out;
    }
}
