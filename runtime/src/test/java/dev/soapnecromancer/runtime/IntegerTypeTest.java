package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** xs:integer: any size up to the digit cap, exact in both directions (parser Requirement 3.1). */
@Tag("fast")
class IntegerTypeTest {
    private static final String NS = "urn:t";
    private static final ElementRef ROOT = new ElementRef(NS, "Root", "Root");
    private static final JsonXmlConverter CONV;

    static {
        Map<String, TypeModel> types = new LinkedHashMap<>();
        types.put("Root", TypeModel.complex("Root", List.of(new FieldModel("n", NS, "xs:integer", 0, 1))));
        for (BuiltIn b : BuiltIn.values())
            types.put(b.typeId(), TypeModel.builtIn(b));
        CONV = new JsonXmlConverter(
                new ServiceModel("S", NS, null, List.of(new OperationModel("Op", "", "", ROOT, ROOT)), types));
    }

    private static String xml(String n) {
        return "<r:Root xmlns:r=\"" + NS + "\"><r:n>" + n + "</r:n></r:Root>";
    }

    @Test
    void valuesBeyondLongSurviveBothDirections() {
        String big = "123456789012345678901234567890123456789";
        assertThat(CONV.toXml(ROOT, Map.of("n", new BigInteger(big)))).contains(">" + big + "<");
        assertThat(CONV.toXml(ROOT, Map.of("n", big))).contains(">" + big + "<");
        Map<?, ?> back = (Map<?, ?>) CONV.toJson(ROOT, xml("-" + big));
        assertThat(back.get("n")).isEqualTo(new BigInteger("-" + big));
    }

    @Test
    void aPlusSignAndSurroundingWhitespaceAreAccepted() {
        Map<?, ?> back = (Map<?, ?>) CONV.toJson(ROOT, xml(" +42 "));
        assertThat(back.get("n")).isEqualTo(BigInteger.valueOf(42));
    }

    @Test
    void aFractionIsRefused() {
        assertThatThrownBy(() -> CONV.toXml(ROOT, Map.of("n", "1.5"))).isInstanceOf(ConversionException.class)
                .hasMessageContaining("expected an integer");
        assertThatThrownBy(() -> CONV.toJson(ROOT, xml("1.5"))).isInstanceOf(ConversionException.class);
    }

    @Test
    void anOverlongNumberIsRefusedBeforeItIsParsed() {
        String tooLong = "9".repeat(JsonXmlConverter.MAX_NUMBER_DIGITS + 5);
        assertThatThrownBy(() -> CONV.toJson(ROOT, xml(tooLong))).isInstanceOf(ConversionException.class)
                .hasMessageContaining("too large");
        assertThatThrownBy(() -> CONV.toXml(ROOT, Map.of("n", new BigInteger(tooLong))))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void notANumberIsRefused() {
        assertThatThrownBy(() -> CONV.toJson(ROOT, xml("abc"))).isInstanceOf(ConversionException.class)
                .hasMessageContaining("xs:integer");
    }
}
