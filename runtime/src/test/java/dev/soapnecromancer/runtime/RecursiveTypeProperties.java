package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.*;
import net.jqwik.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-referencing types (a tree, and two mutually recursive types) round-trip like any other.
 * ModelArbitraries.models() only builds acyclic models, so recursion needs its own fixture.
 * Every generated proxy runs these helpers, so a WSDL with a recursive type must not hang its tests.
 */
@Tag("fast")
class RecursiveTypeProperties {
    private static final String NS = "urn:tree";
    static final ElementRef ROOT = new ElementRef(NS, "Tree", "Node");
    static final ServiceModel MODEL;

    static {
        Map<String, TypeModel> types = new LinkedHashMap<>();
        types.put("Node", TypeModel.complex("Node", List.of(
                new FieldModel("label", NS, "xs:string", 1, 1),
                new FieldModel("child", NS, "Node", 0, FieldModel.UNBOUNDED),
                new FieldModel("note", NS, "Note", 0, 1))));
        // Note <-> Node: mutual recursion through optional fields only, so finite values exist.
        types.put("Note", TypeModel.complex("Note", List.of(
                new FieldModel("text", NS, "xs:string", 1, 1),
                new FieldModel("about", NS, "Node", 0, 1))));
        types.put("xs:string", TypeModel.builtIn(BuiltIn.STRING));
        MODEL = new ServiceModel("Trees", NS, null, List.of(new OperationModel("Grow", "", "", ROOT, ROOT)), types);
    }

    @Provide
    Arbitrary<Object> trees() {
        return ModelArbitraries.values(MODEL, "Node");
    }

    @Property(tries = 200)
    void recursiveValuesRoundTrip(@ForAll("trees") Object tree) {
        JsonXmlConverter conv = new JsonXmlConverter(MODEL);
        assertThat(conv.toJson(ROOT, conv.toXml(ROOT, tree))).isEqualTo(tree);
    }
}
