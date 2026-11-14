package dev.soapnecromancer.core;

import dev.soapnecromancer.runtime.model.SoapVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parser Requirements 2.2, 2.3, 2.8, 2.10 and 2.15 for SOAP 1.2. Requirement 2.3 says SOAP 1.2
 * bindings follow every SOAP 1.1 rule, so the structure rules run for both versions.
 */
class Soap12ParserTest {
    private static final String SOAP12 = "http://schemas.xmlsoap.org/wsdl/soap12/";
    private final WsdlParser parser = new WsdlParser();

    private static Wsdls.Builder builder(boolean soap12) {
        Wsdls.Builder b = Wsdls.wsdl();
        return soap12 ? b.soap12() : b;
    }

    private ParseResult parse(String xml) {
        return parser.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> codes(Throwable e) {
        return ((WsdlParseException) e).warnings().stream().map(Warning::code).toList();
    }

    @ParameterizedTest(name = "soap12={0}")
    @ValueSource(booleans = { false, true })
    void req2_3_req2_4_rpcIsSkippedInBothVersions(boolean soap12) {
        assertThatThrownBy(() -> parser.parse(builder(soap12).style("rpc").build()))
                .satisfies(e -> assertThat(codes(e)).containsExactly("RPC_STYLE"));
    }

    @ParameterizedTest(name = "soap12={0}")
    @ValueSource(booleans = { false, true })
    void req2_3_req2_5_encodedIsSkippedInBothVersions(boolean soap12) {
        assertThatThrownBy(() -> parser.parse(builder(soap12).use("encoded").build()))
                .satisfies(e -> assertThat(codes(e)).containsExactly("ENCODED_USE"));
    }

    @ParameterizedTest(name = "soap12={0}")
    @ValueSource(booleans = { false, true })
    void req2_3_req2_8_headersAreIgnoredInBothVersions(boolean soap12) {
        ParseResult r = parser.parse(builder(soap12)
                .inputExtra("<s:header message=\"tns:OpRequest\" part=\"p\" use=\"literal\"/>").build());
        assertThat(r.warnings()).extracting(Warning::code).containsExactly("SOAP_HEADER");
        assertThat(r.model().soapVersion()).isEqualTo(soap12 ? SoapVersion.SOAP_12 : SoapVersion.SOAP_11);
    }

    @ParameterizedTest(name = "soap12={0}")
    @ValueSource(booleans = { false, true })
    void req2_3_req2_6_bodyPartsAttributeWorksInBothVersions(boolean soap12) {
        String parts = "<wsdl:part name=\"a\" element=\"tns:OpResponse\"/><wsdl:part name=\"p\" element=\"tns:OpRequest\"/>";
        String xml = new String(builder(soap12).inputParts(parts).build(), StandardCharsets.UTF_8).replaceFirst(
                "<wsdl:input><s:body use=\"literal\"/>", "<wsdl:input><s:body use=\"literal\" parts=\"p\"/>");
        assertThat(parse(xml).model().operations().get(0).input().localName()).isEqualTo("OpRequest");
    }

    /** Services such as .NET ASMX publish a SOAP 1.2 port next to the SOAP 1.1 one. */
    @Test
    void req2_10_soap11IsPreferredWhenBothPortsExistWhateverTheOrder() {
        String b12 = """
                <wsdl:binding name="B12" type="tns:PT" xmlns:s12="%s">
                  <s12:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                  <wsdl:operation name="Op"><s12:operation soapAction="urn:t:Op12"/>
                    <wsdl:input><s12:body use="literal"/></wsdl:input><wsdl:output><s12:body use="literal"/></wsdl:output>
                  </wsdl:operation>
                </wsdl:binding>
                <wsdl:service name="S"><wsdl:port name="P12" binding="tns:B12">
                  <s12:address location="http://legacy.example/ws12" xmlns:s12="%s"/>
                </wsdl:port>""".formatted(SOAP12, SOAP12);
        String xml = new String(Wsdls.wsdl().build(), StandardCharsets.UTF_8).replace("<wsdl:service name=\"S\">", b12);
        ParseResult r = parse(xml);
        assertThat(r.model().soapVersion()).isEqualTo(SoapVersion.SOAP_11);
        assertThat(r.model().endpointUrl()).isEqualTo("http://legacy.example/ws");
        assertThat(r.model().operations().get(0).soapAction()).isEqualTo("urn:t:Op");
        assertThat(r.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("MULTIPLE_PORTS");
            assertThat(w.message()).contains("using S/P (SOAP 1.1)").contains("skipped S/P12 (SOAP 1.2)");
        });
    }

    @Test
    void req2_14_soap12PortWithoutAddressWarns() {
        String xml = new String(Wsdls.wsdl().soap12().build(), StandardCharsets.UTF_8)
                .replace("<s:address location=\"http://legacy.example/ws\"/>", "");
        ParseResult r = parse(xml);
        assertThat(r.warnings()).extracting(Warning::code).containsExactly("NO_ADDRESS");
        assertThat(r.model().soapVersion()).isEqualTo(SoapVersion.SOAP_12);
    }

    @Test
    void req2_15_soap12BindingWithoutServiceIsUsed() {
        String xml = new String(Wsdls.wsdl().soap12().build(), StandardCharsets.UTF_8)
                .replaceAll("(?s)<wsdl:service name=\"S\">.*?</wsdl:service>", "");
        ParseResult r = parse(xml);
        assertThat(r.model().soapVersion()).isEqualTo(SoapVersion.SOAP_12);
        assertThat(r.warnings()).extracting(Warning::code).containsExactly("NO_ADDRESS");
    }

    @Test
    void req4_2_soap12ModelSerializesItsVersion() {
        String json = ModelJson.write(parser.parse(Wsdls.wsdl().soap12().build()).model());
        assertThat(json).contains("\"soapVersion\" : \"SOAP_12\"");
    }
}
