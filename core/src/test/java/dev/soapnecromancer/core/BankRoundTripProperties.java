package dev.soapnecromancer.core;

import dev.soapnecromancer.runtime.JsonXmlConverter;
import dev.soapnecromancer.runtime.ModelArbitraries;
import dev.soapnecromancer.runtime.RoundTripAssertions;
import dev.soapnecromancer.runtime.RuntimeXml;
import dev.soapnecromancer.runtime.XmlRenderer;
import dev.soapnecromancer.runtime.model.ElementRef;
import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Converter Requirement 3.4: parser output for bank.wsdl round-trips through the shipped converter,
 * for every operation's input and output. Exercises parser and runtime together, so the Kiro hook
 * catches a parser change that breaks conversion.
 */
@Tag("fast")
class BankRoundTripProperties {
    private static final ServiceModel BANK = new WsdlParser().parse(Wsdls.resource("bank.wsdl")).model();
    private static final JsonXmlConverter CONVERTER = new JsonXmlConverter(BANK);

    record Case(ElementRef root, Object value) {}

    @Provide
    Arbitrary<Case> bankMessages() {
        List<Arbitrary<Case>> perElement = new ArrayList<>();
        for (OperationModel op : BANK.operations()) {
            for (ElementRef ref : List.of(op.input(), op.output())) {
                perElement.add(ModelArbitraries.values(BANK, ref.typeId()).map(v -> new Case(ref, v)));
            }
        }
        return Arbitraries.oneOf(perElement);
    }

    @Property
    void jsonToXmlToJsonIsLossless(@ForAll("bankMessages") Case c) {
        assertThat(CONVERTER.toJson(c.root(), CONVERTER.toXml(c.root(), c.value()))).isEqualTo(c.value());
    }

    @Property
    void anyBackendRenderingReadsBack(@ForAll("bankMessages") Case c, @ForAll Random random) {
        String xml = new XmlRenderer(BANK, random).render(c.root(), c.value());
        assertThat(CONVERTER.toJson(c.root(), xml)).as(xml).isEqualTo(c.value());
    }

    @Property
    void absentOptionalsStayAbsent(@ForAll("bankMessages") Case c) throws Exception {
        String xml = CONVERTER.toXml(c.root(), c.value());
        RoundTripAssertions.assertAbsentOptionalsHaveNoElement(BANK, c.root().typeId(), c.value(),
                RuntimeXml.parse(xml).getDocumentElement());
    }

    @Example
    void everyBankOperationIsCovered() {
        assertThat(BANK.operations()).extracting(OperationModel::name)
                .containsExactly("GetBalance", "Transfer", "GetStatement");
    }
}
