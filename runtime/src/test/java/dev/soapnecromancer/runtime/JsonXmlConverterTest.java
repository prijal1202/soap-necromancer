package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Example-based edge cases for .kiro/specs/converter Requirements 1 and 2. */
@Tag("fast")
class JsonXmlConverterTest {
    private static final String NS = "urn:example:bank:v1";

    // Statement-like model: Root{accountId, maxLines?, flag?, kind?, when?, day?,
    // big?, line[0..*]{amount, memo?}}
    private static final ServiceModel MODEL;
    private static final ElementRef ROOT = new ElementRef(NS, "Root", "Root");

    static {
        Map<String, TypeModel> types = new LinkedHashMap<>();
        types.put("Root", TypeModel.complex("Root", List.of(
                new FieldModel("accountId", NS, "xs:string", 1, 1),
                new FieldModel("maxLines", NS, "xs:int", 0, 1),
                new FieldModel("flag", NS, "xs:boolean", 0, 1),
                new FieldModel("kind", NS, "Kind", 0, 1),
                new FieldModel("when", NS, "xs:dateTime", 0, 1),
                new FieldModel("day", NS, "xs:date", 0, 1),
                new FieldModel("big", "", "xs:long", 0, 1),
                new FieldModel("line", NS, "Line", 0, FieldModel.UNBOUNDED))));
        types.put("Line", TypeModel.complex("Line", List.of(
                new FieldModel("amount", NS, "xs:decimal", 1, 1),
                new FieldModel("memo", NS, "xs:string", 0, 1))));
        types.put("Kind", TypeModel.simple("Kind", BuiltIn.STRING, List.of("DEBIT", "CREDIT")));
        for (BuiltIn b : BuiltIn.values())
            types.put(b.typeId(), TypeModel.builtIn(b));
        OperationModel op = new OperationModel("Op", "", "", ROOT, ROOT);
        MODEL = new ServiceModel("S", NS, null, List.of(op), types);
    }

    private final JsonXmlConverter conv = new JsonXmlConverter(MODEL);

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
            m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private Object roundTrip(Object json) {
        return conv.toJson(ROOT, conv.toXml(ROOT, json));
    }

    // ---- writing

    @Test
    void req1_1_1_2_1_3_rootNamespaceFieldOrderAndUnqualified() {
        String xml = conv.toXml(ROOT, obj("big", 7, "accountId", "A1"));
        assertThat(xml).isEqualTo(
                "<ns1:Root xmlns:ns1=\"" + NS + "\"><ns1:accountId>A1</ns1:accountId><big>7</big></ns1:Root>");
    }

    @Test
    void req1_4_missingRequiredFieldNamesPath() {
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("line", List.of(obj("memo", "x")))))
                .isInstanceOf(ConversionException.class).hasMessageStartingWith("$.accountId");
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "A", "line", List.of(obj("memo", "x")))))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.path()).isEqualTo("$.line[0].amount"));
    }

    @Test
    void req1_5_nullOptionalWritesNothing() {
        Map<String, Object> in = obj("accountId", "A", "maxLines", null);
        assertThat(conv.toXml(ROOT, in)).doesNotContain("maxLines");
    }

    @Test
    void req1_6_repeatedMustBeArray() {
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "A", "line", obj("amount", 1))))
                .hasMessageContaining("expected a JSON array");
    }

    @Test
    void req1_7_unknownKeyRejected() {
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "A", "acountId", "typo")))
                .isInstanceOfSatisfying(ConversionException.class, e -> assertThat(e.path()).isEqualTo("$.acountId"));
    }

    @Test
    void req1_8_numbersAndBooleansAcceptStringsAndCheckRange() {
        assertThat(roundTrip(obj("accountId", "A", "maxLines", "12", "flag", "true")))
                .isEqualTo(obj("accountId", "A", "maxLines", 12, "flag", true, "line", List.of()));
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "A", "maxLines", 3_000_000_000L)))
                .hasMessageContaining("out of range for xs:int");
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "A", "maxLines", 1.5)))
                .hasMessageContaining("expected an integer");
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "A", "flag", "yes")))
                .hasMessageContaining("expected a boolean");
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", 42)))
                .hasMessageContaining("expected a string");
    }

    @Test
    void req1_9_enumValuesListed() {
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "A", "kind", "REFUND")))
                .hasMessageContaining("[DEBIT, CREDIT]");
    }

    @Test
    void req1_10_datesValidated() {
        assertThat(conv.toXml(ROOT, obj("accountId", "A", "day", "2024-02-29", "when", "2024-02-29T10:15:30+05:30")))
                .contains("2024-02-29T10:15:30+05:30");
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "A", "day", "29/02/2024")))
                .hasMessageContaining("ISO-8601 date");
    }

    @Test
    void req1_11_decimalsPlainNotationAndScaleKept() {
        String xml = conv.toXml(ROOT, obj("accountId", "A", "line", List.of(
                obj("amount", new BigDecimal("1E+3")), obj("amount", new BigDecimal("100.50")), obj("amount", 0.1))));
        assertThat(xml).contains(">1000<", ">100.50<", ">0.1<").doesNotContain("E+");
        Object back = conv.toJson(ROOT, xml);
        List<Object> amounts = ((List<?>) ((Map<?, ?>) back).get("line")).stream()
                .map(l -> ((Map<?, ?>) l).get("amount")).map(Object.class::cast).toList();
        assertThat(amounts).containsExactly(new BigDecimal("1000"), new BigDecimal("100.50"), new BigDecimal("0.1"));
    }

    @Test
    void req3_6_bankAmountsAreExact() {
        for (String s : List.of("0.10", "1234567890.123456789")) {
            BigDecimal amount = new BigDecimal(s);
            Map<String, Object> in = obj("accountId", "A", "line", List.of(obj("amount", amount)));
            String xml = conv.toXml(ROOT, in);
            assertThat(xml).contains("<ns1:amount>" + s + "</ns1:amount>");
            Object back = ((Map<?, ?>) ((List<?>) ((Map<?, ?>) conv.toJson(ROOT, xml)).get("line")).get(0))
                    .get("amount");
            assertThat(back).isInstanceOf(BigDecimal.class);
            assertThat(back.toString()).isEqualTo(s); // same digits and scale, not just compareTo == 0
        }
    }

    @Test
    void req1_12_specialCharactersAndCarriageReturnSurvive() {
        String tricky = "a&b <c> \"q\" ]]> \r\n\ttab \uD83D\uDE00 ";
        Map<String, Object> in = obj("accountId", tricky, "line", List.of());
        assertThat(roundTrip(in)).isEqualTo(in);
    }

    @Test
    void req1_13_invalidXmlCharacterRejected() {
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "bell\u0007")))
                .hasMessageContaining("U+0007");
        assertThatThrownBy(() -> conv.toXml(ROOT, obj("accountId", "half\uD83D")))
                .hasMessageContaining("U+D83D");
    }

    // ---- reading

    @Test
    void req2_1_wrongRootRejected() {
        assertThatThrownBy(() -> conv.toJson(ROOT, "<Root><accountId>A</accountId></Root>"))
                .hasMessageContaining("expected element {" + NS + "}Root");
    }

    @Test
    void req2_2_2_3_2_5_anyOrderUnknownIgnoredEmptyList() {
        String xml = "<Root xmlns=\"" + NS + "\"><extra>ignored</extra><flag>1</flag><accountId>A</accountId></Root>";
        assertThat(conv.toJson(ROOT, xml)).isEqualTo(obj("accountId", "A", "flag", true, "line", List.of()));
    }

    @Test
    void req2_4_duplicateSingleFieldRejected() {
        String xml = "<Root xmlns=\"" + NS + "\"><accountId>A</accountId><accountId>B</accountId></Root>";
        assertThatThrownBy(() -> conv.toJson(ROOT, xml)).hasMessageContaining("occurs 2 times");
    }

    @Test
    void req2_6_stringsKeptExactlyOthersTrimmed() {
        String xml = "<Root xmlns=\"" + NS + "\"><accountId>  A  </accountId><maxLines> 5 </maxLines></Root>";
        assertThat(conv.toJson(ROOT, xml)).isEqualTo(obj("accountId", "  A  ", "maxLines", 5, "line", List.of()));
    }

    @Test
    void req2_7_undeclaredEnumFromBackendPassesThrough() {
        String xml = "<Root xmlns=\"" + NS + "\"><accountId>A</accountId><kind>REVERSAL</kind></Root>";
        assertThat(((Map<?, ?>) conv.toJson(ROOT, xml)).get("kind")).isEqualTo("REVERSAL");
    }

    @Test
    void req2_8_doctypeRejected() {
        String xml = "<!DOCTYPE r [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Root xmlns=\"" + NS + "\">&e;</Root>";
        assertThatThrownBy(() -> conv.toJson(ROOT, xml)).isInstanceOf(ConversionException.class)
                .hasMessageContaining("invalid XML");
    }

    @Test
    void emptyStatementRoundTrips() {
        Map<String, Object> in = obj("accountId", "ACC-2001", "line", List.of());
        String xml = conv.toXml(ROOT, in);
        assertThat(xml).doesNotContain("line");
        assertThat(conv.toJson(ROOT, xml)).isEqualTo(in);
    }
}
