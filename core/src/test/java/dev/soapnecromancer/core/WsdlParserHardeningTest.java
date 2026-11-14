package dev.soapnecromancer.core;

import dev.soapnecromancer.runtime.model.FieldModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Production-review hardening of the parser (parser tasks.md, task 8). Requirement numbers in each
 * test name refer to .kiro/specs/parser/requirements.md.
 */
class WsdlParserHardeningTest {
    private final WsdlParser parser = new WsdlParser();

    private static List<String> codes(ParseResult r) {
        return r.warnings().stream().map(Warning::code).toList();
    }

    private static String wsdl(Wsdls.Builder b) {
        return new String(b.build(), StandardCharsets.UTF_8);
    }

    private ParseResult parse(String xml) {
        return parser.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    /** Requirement 1.6: a result or a WsdlParseException, never any other exception. */
    private void parsesOrFailsCleanly(String xml) {
        try {
            parse(xml);
        } catch (WsdlParseException expected) {
            // fine: a 422 with a reason
        } catch (RuntimeException e) {
            fail("expected a result or WsdlParseException, got " + e, e);
        }
    }

    private static final String RESPONSE = """
            <xs:element name="OpResponse"><xs:complexType><xs:sequence>
              <xs:element name="ok" type="xs:boolean"/>
            </xs:sequence></xs:complexType></xs:element>""";

    // ------------------------------------------------------------------ Requirement 1

    /** WSDL4J parsed every wsdl:types but the fix only removed the first one: SSRF through the second. */
    @Test
    void req1_5_secondTypesBlockCannotReachTheNetwork() {
        try (SsrfCanary canary = new SsrfCanary()) {
            String evil = "<wsdl:types><xs:schema targetNamespace=\"urn:evil\">"
                    + "<xs:import namespace=\"urn:x\" schemaLocation=\"" + canary.url("second-types.xsd") + "\"/>"
                    + "<xs:include schemaLocation=\"" + canary.url("second-include.xsd") + "\"/>"
                    + "</xs:schema></wsdl:types>";
            String xml = wsdl(Wsdls.wsdl()).replace("</wsdl:types>", "</wsdl:types>" + evil);
            ParseResult r = parse(xml);
            assertThat(canary.hits()).as("requests received by the canary").isEmpty();
            assertThat(r.model().operations()).hasSize(1);
            assertThat(r.warnings()).filteredOn(w -> w.code().equals("EXTERNAL_IMPORT"))
                    .extracting(Warning::location).anySatisfy(l -> assertThat(l).contains("second-types.xsd"));
        }
    }

    @Test
    void req1_6_invalidOccurrenceTextIsAFatalErrorNotACrash() {
        String xml = wsdl(Wsdls.wsdl().types("""
                <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                  <xs:element name="id" type="xs:string" minOccurs="x"/>
                </xs:sequence></xs:complexType></xs:element>""" + RESPONSE));
        parsesOrFailsCleanly(xml);
    }

    @Test
    void req1_6_undeclaredPrefixIsAFatalErrorOrWarningNotACrash() {
        parsesOrFailsCleanly(wsdl(Wsdls.wsdl().types("""
                <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                  <xs:element name="id" type="nope:Missing"/>
                </xs:sequence></xs:complexType></xs:element>""" + RESPONSE)));
        parsesOrFailsCleanly(wsdl(Wsdls.wsdl().types("""
                <xs:simpleType name="S"><xs:restriction base="nope:Base"/></xs:simpleType>
                <xs:element name="OpRequest" type="tns:S"/>""" + RESPONSE)));
    }

    /** Unresolved schemas got ids from hashCode(): "Aa" and "BB" collided and XmlSchema threw. */
    @Test
    void req1_6_sameOrCollidingSchemaLocationsTwiceStillParse() {
        String imports = """
                <xs:schema targetNamespace="urn:a"><xs:import namespace="urn:c" schemaLocation="Aa"/></xs:schema>
                <xs:schema targetNamespace="urn:b"><xs:import namespace="urn:c" schemaLocation="BB"/></xs:schema>
                <xs:schema targetNamespace="urn:d"><xs:import namespace="urn:c" schemaLocation="Aa"/></xs:schema>""";
        ParseResult r = parse(wsdl(Wsdls.wsdl().extraSchemas(imports)));
        assertThat(r.model().operations()).hasSize(1);
        assertThat(codes(r)).contains("EXTERNAL_IMPORT");
    }

    @Test
    void req1_6_namelessServiceAndPortAreSkippedWithoutACrash() {
        String xml = wsdl(Wsdls.wsdl()).replace("<wsdl:service name=\"S\"><wsdl:port name=\"P\"",
                "<wsdl:service><wsdl:port");
        ParseResult r = parse(xml);
        assertThat(codes(r)).contains("UNSUPPORTED_BINDING", "NO_ADDRESS");
        assertThat(r.model().endpointUrl()).isNull();
        assertThat(r.model().operations()).hasSize(1);
    }

    // ------------------------------------------------------------------ Requirement 2

    /** WSDL4J keeps services in a HashMap; the choice must follow the document, not the hash. */
    @Test
    void req2_10_firstPortInDocumentOrderWinsAndTheOthersAreNamed() {
        String services = """
                <wsdl:service name="Zeta"><wsdl:port name="Z" binding="tns:B"><s:address location="http://zeta/ws"/></wsdl:port></wsdl:service>
                <wsdl:service name="Alpha"><wsdl:port name="A" binding="tns:B"><s:address location="http://alpha/ws"/></wsdl:port></wsdl:service>
                """;
        String xml = wsdl(Wsdls.wsdl()).replaceAll("(?s)<wsdl:service name=\"S\">.*?</wsdl:service>", services);
        ParseResult r = parse(xml);
        assertThat(r.model().endpointUrl()).isEqualTo("http://zeta/ws");
        assertThat(r.model().name()).isEqualTo("Zeta");
        assertThat(r.warnings()).filteredOn(w -> w.code().equals("MULTIPLE_PORTS")).singleElement()
                .extracting(Warning::message).asString().contains("using Zeta/Z").contains("skipped Alpha/A");
    }

    @Test
    void req2_13_nonSoapBindingIsSkippedWithAWarning() {
        String http = """
                <wsdl:binding name="H" type="tns:PT" xmlns:http="http://schemas.xmlsoap.org/wsdl/http/">
                  <http:binding verb="POST"/>
                </wsdl:binding>
                <wsdl:service name="Web"><wsdl:port name="HP" binding="tns:H"/></wsdl:service>""";
        String xml = wsdl(Wsdls.wsdl()).replace("<wsdl:service name=\"S\">", http + "<wsdl:service name=\"S\">");
        ParseResult r = parse(xml);
        assertThat(r.warnings()).filteredOn(w -> w.code().equals("UNSUPPORTED_BINDING")).singleElement()
                .extracting(Warning::location).isEqualTo("service[Web]/port[HP]");
        assertThat(r.model().endpointUrl()).isEqualTo("http://legacy.example/ws");
    }

    @Test
    void req2_14_chosenPortWithoutAddressWarns() {
        String xml = wsdl(Wsdls.wsdl()).replace("<s:address location=\"http://legacy.example/ws\"/>", "");
        ParseResult r = parse(xml);
        assertThat(codes(r)).containsExactly("NO_ADDRESS");
        assertThat(r.model().endpointUrl()).isNull();
    }

    @Test
    void req2_15_withoutServicesTheFirstBindingIsUsedAlone() {
        String second = """
                <wsdl:binding name="B2" type="tns:PT">
                  <s:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                  <wsdl:operation name="Op"><s:operation soapAction="urn:t:Op2"/>
                    <wsdl:input><s:body use="literal"/></wsdl:input><wsdl:output><s:body use="literal"/></wsdl:output>
                  </wsdl:operation>
                </wsdl:binding>""";
        String xml = wsdl(Wsdls.wsdl()).replaceAll("(?s)<wsdl:service name=\"S\">.*?</wsdl:service>", second);
        ParseResult r = parse(xml);
        assertThat(r.model().operations()).singleElement().extracting("soapAction").isEqualTo("urn:t:Op");
        assertThat(codes(r)).contains("NO_ADDRESS", "MULTIPLE_PORTS");
    }

    @Test
    void req2_16_duplicateOperationNameKeepsTheFirst() {
        String dup = """
                <wsdl:operation name="Op"><s:operation soapAction="urn:t:Again"/>
                  <wsdl:input><s:body use="literal"/></wsdl:input><wsdl:output><s:body use="literal"/></wsdl:output>
                </wsdl:operation>
                </wsdl:binding>""";
        String xml = wsdl(Wsdls.wsdl()).replace("</wsdl:binding>", dup);
        ParseResult r = parse(xml);
        assertThat(r.model().operations()).singleElement().extracting("soapAction").isEqualTo("urn:t:Op");
        assertThat(codes(r)).containsExactly("DUPLICATE_OPERATION");
    }

    @Test
    void req2_17_operationNamesThatAreNotUrlSafeAreSkipped() {
        for (String bad : List.of("Get Balance", "{id}", "a/b", "1Op", "x".repeat(129))) {
            String xml = wsdl(Wsdls.wsdl()).replace("name=\"Op\"", "name=\"" + bad + "\"");
            assertThatThrownBy(() -> parse(xml)).as(bad).isInstanceOfSatisfying(WsdlParseException.class,
                    e -> assertThat(e.warnings()).extracting(Warning::code).containsExactly("UNSUPPORTED_NAME"));
        }
        assertThat(parse(wsdl(Wsdls.wsdl()).replace("name=\"Op\"", "name=\"Get_Balance.v2-x\"")).model()
                .operations()).hasSize(1);
    }

    /** The common header pattern: one message, a header part and a body part. */
    @Test
    void req2_6_headerPartInTheBodyMessageIsNotCountedAsBody() {
        String parts = "<wsdl:part name=\"h\" element=\"tns:OpResponse\"/><wsdl:part name=\"p\" element=\"tns:OpRequest\"/>";
        ParseResult r = parse(wsdl(Wsdls.wsdl().inputParts(parts)
                .inputExtra("<s:header message=\"tns:OpRequest\" part=\"h\" use=\"literal\"/>")));
        assertThat(codes(r)).containsExactly("SOAP_HEADER");
        assertThat(r.model().operations().get(0).input().localName()).isEqualTo("OpRequest");
    }

    @Test
    void req2_6_soapBodyPartsAttributeSelectsTheBodyPart() {
        String parts = "<wsdl:part name=\"a\" element=\"tns:OpResponse\"/><wsdl:part name=\"p\" element=\"tns:OpRequest\"/>";
        String xml = wsdl(Wsdls.wsdl().inputParts(parts)).replaceFirst(
                "<wsdl:input><s:body use=\"literal\"/>", "<wsdl:input><s:body use=\"literal\" parts=\"p\"/>");
        ParseResult r = parse(xml);
        assertThat(r.warnings()).isEmpty();
        assertThat(r.model().operations().get(0).input().localName()).isEqualTo("OpRequest");
    }

    // ------------------------------------------------------------------ Requirement 3

    @Test
    void req3_12_elementNamesThatAreNotXmlNamesAreDropped() {
        ParseResult r = parse(wsdl(Wsdls.wsdl().types("""
                <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                  <xs:element name="keep" type="xs:string"/>
                  <xs:element name="a&quot;&gt;&lt;x" type="xs:string"/>
                </xs:sequence></xs:complexType></xs:element>""" + RESPONSE)));
        assertThat(codes(r)).containsExactly("XSD_INVALID_NAME");
        assertThat(r.model().type("OpRequest").fields()).extracting("name").containsExactly("keep");
    }

    @Test
    void req3_13_occurrenceBoundsAreClampedWithAWarning() {
        ParseResult r = parse(wsdl(Wsdls.wsdl().types("""
                <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                  <xs:element name="many" type="xs:string" minOccurs="5000" maxOccurs="unbounded"/>
                  <xs:element name="gone" type="xs:string" minOccurs="0" maxOccurs="0"/>
                  <xs:element name="ok" type="xs:string" minOccurs="2" maxOccurs="3"/>
                </xs:sequence></xs:complexType></xs:element>""" + RESPONSE)));
        assertThat(codes(r)).containsExactly("XSD_OCCURS", "XSD_OCCURS");
        assertThat(r.model().type("OpRequest").fields()).containsExactly(
                new FieldModel("many", "urn:t", "xs:string", 1000, FieldModel.UNBOUNDED),
                new FieldModel("ok", "urn:t", "xs:string", 2, 3));
    }
}
