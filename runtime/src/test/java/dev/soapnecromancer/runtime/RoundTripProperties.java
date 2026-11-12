package dev.soapnecromancer.runtime;

import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Converter Requirement 3.1-3.3 over random models in the supported XSD subset.
 * Tagged "fast": the Kiro hook runs these with -Djqwik.tries=100 on every save
 * in core/runtime.
 */
@Tag("fast")
class RoundTripProperties {

    record Case(ModelArbitraries.Generated g, Object value) {
    }

    @Provide
    Arbitrary<Case> cases() {
        return ModelArbitraries.models()
                .flatMap(g -> ModelArbitraries.values(g.model(), g.root().typeId()).map(v -> new Case(g, v)));
    }

    /** 3.1: toJson(toXml(v)) == v. */
    @Property
    void jsonToXmlToJsonIsLossless(@ForAll("cases") Case c) {
        JsonXmlConverter conv = new JsonXmlConverter(c.g().model());
        String xml = conv.toXml(c.g().root(), c.value());
        assertThat(conv.toJson(c.g().root(), xml)).isEqualTo(c.value());
    }

    /**
     * 3.2: any legal rendering reads back to v, and survives a second round trip.
     */
    @Property
    void anyXmlRenderingReadsBackToTheSameJson(@ForAll("cases") Case c, @ForAll Random random) {
        JsonXmlConverter conv = new JsonXmlConverter(c.g().model());
        String rendered = new XmlRenderer(c.g().model(), random).render(c.g().root(), c.value());

        Object json = conv.toJson(c.g().root(), rendered);
        assertThat(json).as("read of %s", rendered).isEqualTo(c.value());
        assertThat(conv.toJson(c.g().root(), conv.toXml(c.g().root(), json))).isEqualTo(c.value());
    }

    /**
     * 3.6: decimals are exact. Same digits and same scale after a round trip
     * (compareTo is not enough:
     * 0.10 must not come back as 0.1), and the XML text is the plain string with no
     * exponent.
     */
    @Property
    void decimalsSurviveExactly(@ForAll("decimal") BigDecimal amount) {
        var ref = new dev.soapnecromancer.runtime.model.ElementRef("urn:bank", "Amount", "xs:decimal");
        var model = new dev.soapnecromancer.runtime.model.ServiceModel("S", "urn:bank", null,
                java.util.List.of(new dev.soapnecromancer.runtime.model.OperationModel("Op", "", "", ref, ref)),
                java.util.Map.of("xs:decimal", dev.soapnecromancer.runtime.model.TypeModel.builtIn(
                        dev.soapnecromancer.runtime.model.BuiltIn.DECIMAL)));
        JsonXmlConverter conv = new JsonXmlConverter(model);
        String xml = conv.toXml(ref, amount);
        assertThat(xml).contains(">" + amount.toPlainString() + "<");
        Object back = conv.toJson(ref, xml);
        assertThat(back).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) back).unscaledValue()).isEqualTo(amount.unscaledValue());
        assertThat(((BigDecimal) back).scale()).isEqualTo(amount.scale());
    }

    @Provide
    Arbitrary<BigDecimal> decimal() {
        return ModelArbitraries.decimals();
    }

    /** 3.3: absent optionals stay absent in the XML. */
    @Property
    void absentOptionalsProduceNoElements(@ForAll("cases") Case c) throws Exception {
        JsonXmlConverter conv = new JsonXmlConverter(c.g().model());
        String xml = conv.toXml(c.g().root(), c.value());
        RoundTripAssertions.assertAbsentOptionalsHaveNoElement(c.g().model(), c.g().root().typeId(), c.value(),
                RuntimeXml.parse(xml).getDocumentElement());
    }
}
