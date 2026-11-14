package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production-review hardening: converter Requirements 1.8, 1.10, 1.14,
 * 2.8-2.12.
 */
@Tag("fast")
class ConverterHardeningTest {
    private static final String NS = "urn:t";
    private static final ElementRef ROOT = new ElementRef(NS, "Root", "Root");
    private static final ElementRef TREE = new ElementRef(NS, "Tree", "Node");
    private static final ServiceModel MODEL;

    static {
        Map<String, TypeModel> types = new LinkedHashMap<>();
        types.put("Root", TypeModel.complex("Root", List.of(
                new FieldModel("amount", NS, "xs:decimal", 0, 1),
                new FieldModel("count", NS, "xs:int", 0, 1),
                new FieldModel("label", NS, "xs:string", 0, 1),
                new FieldModel("when", NS, "xs:dateTime", 0, 1),
                new FieldModel("tags", NS, "xs:string", 0, FieldModel.UNBOUNDED))));
        types.put("Node", TypeModel.complex("Node", List.of(new FieldModel("child", NS, "Node", 0, 1))));
        for (BuiltIn b : BuiltIn.values())
            types.put(b.typeId(), TypeModel.builtIn(b));
        MODEL = new ServiceModel("S", NS, null, List.of(new OperationModel("Op", "", "", ROOT, ROOT)), types);
    }

    private final JsonXmlConverter conv = new JsonXmlConverter(MODEL);

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
            m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String root(String children) {
        return "<r:Root xmlns:r=\"" + NS + "\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" + children
                + "</r:Root>";
    }

    /**
     * A 13-byte number used to make toPlainString / toBigIntegerExact build about a
     * gigabyte.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void req1_8_hugeExponentsAreRejectedBeforeAnyWork() {
        for (Object amount : List.of(new BigDecimal("1e999999999"), "1e999999999", new BigDecimal("1e-999999999"))) {
            assertThatThrownBy(() -> conv.toXml(ROOT, obj("amount", amount))).as(String.valueOf(amount))
                    .isInstanceOf(ConversionException.class).hasMessageContaining("too large or too precise");
            assertThatThrownBy(() -> conv.toXml(ROOT, obj("count", amount))).as(String.valueOf(amount))
                    .isInstanceOf(ConversionException.class);
        }
        assertThat(conv.toXml(ROOT, obj("amount", new BigDecimal("1E+3")))).contains(">1000<");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void req2_10_hugeExponentInAResponseIsRejected() {
        assertThatThrownBy(() -> conv.toJson(ROOT, root("<r:amount>1E+999999999</r:amount>")))
                .isInstanceOf(ConversionException.class).hasMessageContaining("too large");
        assertThat(conv.toJson(ROOT, root("<r:amount>1.5E+2</r:amount>")))
                .isEqualTo(obj("amount", new BigDecimal("1.5E+2"), "tags", List.of()));
    }

    @Test
    void req1_10_dateTimeWithABracketedZoneIdIsRejected() {
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("when", "2024-01-31T10:15:30+01:00[Europe/Paris]")))
                .isInstanceOf(ConversionException.class).hasMessageContaining("ISO-8601 date-time");
        assertThat(conv.toXml(ROOT, obj("when", "2024-01-31T10:15:30"))).contains("2024-01-31T10:15:30");
        assertThat(conv.toXml(ROOT, obj("when", "2024-01-31T10:15:30Z"))).contains("2024-01-31T10:15:30Z");
    }

    @Test
    void req1_14_deeplyNestedRequestIsRejected() {
        Map<String, Object> value = obj();
        Map<String, Object> current = value;
        for (int i = 0; i < JsonXmlConverter.MAX_DEPTH + 10; i++) {
            Map<String, Object> next = obj();
            current.put("child", next);
            current = next;
        }
        assertThatThrownBy(() -> conv.toXml(TREE, value)).isInstanceOf(ConversionException.class)
                .hasMessageContaining("deeper than");
    }

    @Test
    void req2_8_deeplyNestedResponseIsRejected() {
        int depth = JsonXmlConverter.MAX_DEPTH + 10;
        String xml = "<t:Tree xmlns:t=\"" + NS + "\">" + "<t:child>".repeat(depth) + "</t:child>".repeat(depth)
                + "</t:Tree>";
        assertThatThrownBy(() -> conv.toJson(TREE, xml)).isInstanceOf(ConversionException.class);
    }

    @Test
    void req2_9_xsiNilIsAbsent() {
        Object json = conv.toJson(ROOT, root("""
                <r:amount xsi:nil="true"/><r:count xsi:nil="1"/><r:label xsi:nil="false">x</r:label>
                <r:tags>a</r:tags><r:tags xsi:nil="true"/><r:tags>b</r:tags>"""));
        assertThat(json).isEqualTo(obj("label", "x", "tags", List.of("a", "b")));
    }

    @Test
    void req2_12_errorMessagesQuoteAtMost40CharactersOfBackendText() {
        String secret = "java.lang.IllegalStateException: secret internal detail " + "x".repeat(200);
        assertThatThrownBy(() -> conv.toJson(ROOT, root("<r:count>" + secret + "</r:count>")))
                .isInstanceOfSatisfying(ConversionException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("secret internal detail");
                    assertThat(e.getMessage().length()).isLessThan(120);
                });
    }

    @Test
    void plusSignedIntegersStillReadButDoubleSignsDoNot() {
        assertThat(conv.toJson(ROOT, root("<r:count>+5</r:count>"))).isEqualTo(obj("count", 5, "tags", List.of()));
        assertThatThrownBy(() -> conv.toJson(ROOT, root("<r:count>+-5</r:count>")))
                .isInstanceOf(ConversionException.class);
    }
}
