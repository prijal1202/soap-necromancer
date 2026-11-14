package dev.soapnecromancer.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soapnecromancer.runtime.model.BuiltIn;
import dev.soapnecromancer.runtime.model.FieldModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import dev.soapnecromancer.runtime.model.SoapVersion;
import dev.soapnecromancer.runtime.model.TypeModel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers .kiro/specs/parser/requirements.md. Requirement numbers in each test
 * name.
 */
class WsdlParserTest {
        private final WsdlParser parser = new WsdlParser();

        private ParseResult parse(byte[] wsdl) {
                return parser.parse(wsdl);
        }

        private static List<String> codes(ParseResult r) {
                return r.warnings().stream().map(Warning::code).toList();
        }

        @Nested
        class BankGolden {
                @Test
                void req4_3_bankWsdlMatchesGoldenModelWithNoWarnings() throws Exception {
                        ParseResult r = parse(Wsdls.resource("bank.wsdl"));
                        assertThat(r.warnings()).isEmpty();
                        JsonNode actual = ModelJson.mapper().readTree(ModelJson.write(r.model()));
                        JsonNode golden = ModelJson.mapper().readTree(Wsdls.text("bank.model.json"));
                        assertThat(actual).isEqualTo(golden);
                }

                @Test
                void req4_2_serializationIsDeterministic() {
                        String a = ModelJson.write(parse(Wsdls.resource("bank.wsdl")).model());
                        String b = ModelJson.write(parse(Wsdls.resource("bank.wsdl")).model());
                        assertThat(a).isEqualTo(b);
                }

                @Test
                void req2_12_operationsKeepNameActionDocumentationAndElements() {
                        ServiceModel m = parse(Wsdls.resource("bank.wsdl")).model();
                        assertThat(m.operations()).extracting("name").containsExactly("GetBalance", "Transfer",
                                        "GetStatement");
                        var transfer = m.operation("Transfer");
                        assertThat(transfer.soapAction()).isEqualTo("urn:example:bank:v1:Transfer");
                        assertThat(transfer.documentation()).isEqualTo("Moves funds between two accounts.");
                        assertThat(transfer.input().localName()).isEqualTo("TransferRequest");
                        assertThat(m.endpointUrl()).isEqualTo("http://localhost:8080/mock-bank/ws");
                }
        }

        @Nested
        class Security {
                @Test
                void req1_1_doctypeIsFatal() {
                        byte[] evil = ("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                                        + "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\">&x;</wsdl:definitions>")
                                        .getBytes(StandardCharsets.UTF_8);
                        assertThatThrownBy(() -> parse(evil)).isInstanceOf(WsdlParseException.class)
                                        .hasMessageContaining("DOCTYPE");
                }

                @Test
                void req1_4_malformedXmlIsFatal() {
                        assertThatThrownBy(() -> parse("<definitions".getBytes(StandardCharsets.UTF_8)))
                                        .isInstanceOf(WsdlParseException.class).hasMessageStartingWith("Invalid XML");
                }

                @Test
                void req1_2_1_3_externalSchemaImportIsNotFetchedAndWarns() {
                        // A reachable-looking URL: if the parser fetched it, the test would hang or
                        // fail on network.
                        ParseResult r = parse(Wsdls.wsdl()
                                        .extraSchemas("<xs:schema targetNamespace=\"urn:ext\"><xs:import namespace=\"urn:other\" "
                                                        + "schemaLocation=\"http://169.254.169.254/latest/meta-data\"/></xs:schema>")
                                        .build());
                        assertThat(r.warnings()).anySatisfy(w -> {
                                assertThat(w.code()).isEqualTo("EXTERNAL_IMPORT");
                                assertThat(w.location()).contains("169.254.169.254");
                        });
                }

                /**
                 * The real SSRF guarantee: every fetch vector points at a reachable localhost
                 * server, so a
                 * fetch would succeed and be counted (unlike 169.254.169.254, which is only
                 * unreachable on
                 * a laptop). Zero hits, one EXTERNAL_IMPORT warning per vector, operation still
                 * parsed.
                 */
                @Test
                void req1_2_1_3_noFetchVectorReachesTheNetwork() {
                        try (SsrfCanary canary = new SsrfCanary()) {
                                ParseResult r = null;
                                RuntimeException failure = null;
                                try {
                                        r = parse(canary.wsdl());
                                } catch (RuntimeException e) {
                                        failure = e; // e.g. metadata IP unreachable on a laptop; hits are what matter
                                }
                                assertThat(canary.hits()).as("requests received by the canary").isEmpty();
                                assertThat(failure).as("parse failure").isNull();
                                assertThat(r.warnings()).filteredOn(w -> w.code().equals("EXTERNAL_IMPORT")).hasSize(6);
                                assertThat(r.warnings()).extracting(Warning::location)
                                                .anySatisfy(l -> assertThat(l).contains("169.254.169.254"));
                                assertThat(r.model().operations()).hasSize(1);
                        }
                }

                @Test
                void req1_3_wsdlImportIsNotFetchedAndWarns() {
                        ParseResult r = parse(Wsdls.wsdl()
                                        .extraRoot("<wsdl:import namespace=\"urn:x\" location=\"file:///c:/secret.wsdl\"/>")
                                        .build());
                        assertThat(codes(r)).contains("EXTERNAL_IMPORT");
                }
        }

        @Nested
        class Structure {
                @Test
                void req2_1_wsdl20IsFatal() {
                        byte[] w20 = "<description xmlns=\"http://www.w3.org/ns/wsdl\"/>"
                                        .getBytes(StandardCharsets.UTF_8);
                        assertThatThrownBy(() -> parse(w20)).isInstanceOf(WsdlParseException.class)
                                        .hasMessageContaining("WSDL 2.0");
                }

                @Test
                void req2_2_req2_3_soap12OnlyIsParsedAndRecorded() {
                        ParseResult r = parse(Wsdls.wsdl().soap12().build());
                        assertThat(r.warnings()).isEmpty();
                        assertThat(r.model().soapVersion()).isEqualTo(SoapVersion.SOAP_12);
                        assertThat(r.model().endpointUrl()).isEqualTo("http://legacy.example/ws");
                        assertThat(r.model().operations()).singleElement().extracting("soapAction")
                                        .isEqualTo("urn:t:Op");
                }

                @Test
                void req2_3_soap11IsRecordedToo() {
                        assertThat(parse(Wsdls.wsdl().build()).model().soapVersion()).isEqualTo(SoapVersion.SOAP_11);
                }

                @Test
                void req2_11_noSoapBindingAtAllIsFatal() {
                        byte[] http = new String(Wsdls.wsdl().build(), StandardCharsets.UTF_8)
                                        .replace("http://schemas.xmlsoap.org/wsdl/soap/",
                                                        "http://schemas.xmlsoap.org/wsdl/http/")
                                        .getBytes(StandardCharsets.UTF_8);
                        assertThatThrownBy(() -> parse(http)).isInstanceOf(WsdlParseException.class)
                                        .hasMessageContaining("SOAP 1.1 or 1.2");
                }

                @Test
                void req2_4_rpcStyleIsSkipped() {
                        assertThatThrownBy(() -> parse(Wsdls.wsdl().style("rpc").build()))
                                        .isInstanceOfSatisfying(WsdlParseException.class,
                                                        e -> assertThat(e.warnings()).extracting(Warning::code)
                                                                        .containsExactly("RPC_STYLE"));
                }

                @Test
                void req2_5_encodedUseIsSkipped() {
                        assertThatThrownBy(() -> parse(Wsdls.wsdl().use("encoded").build()))
                                        .isInstanceOfSatisfying(WsdlParseException.class,
                                                        e -> assertThat(e.warnings()).extracting(Warning::code)
                                                                        .containsExactly("ENCODED_USE"));
                }

                @Test
                void req2_6_multiPartMessageIsSkipped() {
                        String parts = "<wsdl:part name=\"a\" element=\"tns:OpRequest\"/><wsdl:part name=\"b\" element=\"tns:OpRequest\"/>";
                        assertThatThrownBy(() -> parse(Wsdls.wsdl().inputParts(parts).build()))
                                        .isInstanceOfSatisfying(WsdlParseException.class,
                                                        e -> assertThat(e.warnings()).extracting(Warning::code)
                                                                        .containsExactly("UNSUPPORTED_MESSAGE"));
                }

                @Test
                void req2_6_typePartIsSkipped() {
                        assertThatThrownBy(
                                        () -> parse(Wsdls.wsdl()
                                                        .inputParts("<wsdl:part name=\"a\" type=\"xs:string\"/>")
                                                        .build()))
                                        .isInstanceOfSatisfying(WsdlParseException.class,
                                                        e -> assertThat(e.warnings().get(0).message())
                                                                        .contains("type="));
                }

                @Test
                void req2_7_oneWayIsSkipped() {
                        assertThatThrownBy(() -> parse(Wsdls.wsdl().oneWay().build()))
                                        .isInstanceOfSatisfying(WsdlParseException.class,
                                                        e -> assertThat(e.warnings()).extracting(Warning::code)
                                                                        .containsExactly("ONE_WAY_OPERATION"));
                }

                @Test
                void req2_8_soapHeaderIsIgnoredButOperationKept() {
                        ParseResult r = parse(Wsdls.wsdl()
                                        .inputExtra("<s:header message=\"tns:OpRequest\" part=\"p\" use=\"literal\"/>")
                                        .build());
                        assertThat(codes(r)).containsExactly("SOAP_HEADER");
                        assertThat(r.model().operations()).hasSize(1);
                }

                @Test
                void req2_9_wsSecurityPolicyWarnsButKeepsOperations() {
                        ParseResult r = parse(Wsdls.wsdl()
                                        .extraRoot(
                                                        """
                                                                        <wsp:Policy xmlns:wsp="http://www.w3.org/ns/ws-policy" xmlns:sp="http://docs.oasis-open.org/ws-sx/ws-securitypolicy/200702">
                                                                          <sp:UsernameToken/>
                                                                        </wsp:Policy>""")
                                        .build());
                        assertThat(codes(r)).containsExactly("WS_SECURITY");
                        assertThat(r.warnings().get(0).location()).isEqualTo("definitions/Policy");
                        assertThat(r.model().operations()).hasSize(1);
                }

                @Test
                void req2_2_endpointFromSoapAddress() {
                        assertThat(parse(Wsdls.wsdl().build()).model().endpointUrl())
                                        .isEqualTo("http://legacy.example/ws");
                }
        }

        @Nested
        class Xsd {
                private ParseResult withTypes(String types) {
                        return parse(Wsdls.wsdl().types(types).build());
                }

                private static final String RESPONSE = """
                                <xs:element name="OpResponse"><xs:complexType><xs:sequence>
                                  <xs:element name="ok" type="xs:boolean"/>
                                </xs:sequence></xs:complexType></xs:element>""";

                @Test
                void req3_1_builtInsMap() {
                        ParseResult r = withTypes("""
                                        <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                                          <xs:element name="s" type="xs:string"/><xs:element name="i" type="xs:int"/>
                                          <xs:element name="l" type="xs:long"/><xs:element name="d" type="xs:decimal"/>
                                          <xs:element name="b" type="xs:boolean"/><xs:element name="dt" type="xs:date"/>
                                          <xs:element name="ts" type="xs:dateTime"/>
                                        </xs:sequence></xs:complexType></xs:element>""" + RESPONSE);
                        assertThat(r.warnings()).isEmpty();
                        List<String> typeIds = r.model().type("OpRequest").fields().stream().map(FieldModel::typeId)
                                        .toList();
                        assertThat(typeIds).containsExactly("xs:string", "xs:int", "xs:long", "xs:decimal",
                                        "xs:boolean", "xs:date",
                                        "xs:dateTime");
                        assertThat(r.model().type("xs:dateTime").base()).isEqualTo(BuiltIn.DATE_TIME);
                }

                @Test
                void req3_2_otherBuiltInBecomesStringWithWarning() {
                        ParseResult r = withTypes("""
                                        <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                                          <xs:element name="blob" type="xs:base64Binary"/>
                                        </xs:sequence></xs:complexType></xs:element>""" + RESPONSE);
                        assertThat(codes(r)).containsExactly("UNSUPPORTED_BUILTIN");
                        assertThat(r.model().type("OpRequest").fields().get(0).typeId()).isEqualTo("xs:string");
                }

                @Test
                void req3_3_3_4_occursAndEnumsPreserved() {
                        ParseResult r = withTypes(
                                        """
                                                        <xs:simpleType name="Color"><xs:restriction base="xs:string">
                                                          <xs:enumeration value="RED"/><xs:enumeration value="GREEN"/><xs:enumeration value="BLUE"/>
                                                        </xs:restriction></xs:simpleType>
                                                        <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                                                          <xs:element name="c" type="tns:Color" minOccurs="0" maxOccurs="3"/>
                                                          <xs:element name="tags" type="xs:string" minOccurs="0" maxOccurs="unbounded"/>
                                                        </xs:sequence></xs:complexType></xs:element>"""
                                                        + RESPONSE);
                        List<FieldModel> f = r.model().type("OpRequest").fields();
                        assertThat(f.get(0)).isEqualTo(new FieldModel("c", "urn:t", "Color", 0, 3));
                        assertThat(f.get(1).maxOccurs()).isEqualTo(FieldModel.UNBOUNDED);
                        assertThat(r.model().type("Color").enumValues()).containsExactly("RED", "GREEN", "BLUE");
                }

                @Test
                void req3_5_3_6_refsAnonymousTypesAndUnqualifiedForm() {
                        ParseResult r = parse(Wsdls.wsdl().types("""
                                        <xs:element name="Shared" type="xs:int"/>
                                        <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                                          <xs:element ref="tns:Shared"/>
                                          <xs:element name="local" form="unqualified" type="xs:string"/>
                                          <xs:element name="inner"><xs:complexType><xs:sequence>
                                            <xs:element name="x" type="xs:string"/>
                                          </xs:sequence></xs:complexType></xs:element>
                                        </xs:sequence></xs:complexType></xs:element>""" + RESPONSE).build());
                        List<FieldModel> f = r.model().type("OpRequest").fields();
                        assertThat(f.get(0)).isEqualTo(new FieldModel("Shared", "urn:t", "xs:int", 1, 1));
                        assertThat(f.get(1).namespace()).isEmpty();
                        assertThat(f.get(2).typeId()).isEqualTo("OpRequestInner");
                        assertThat(r.model().type("OpRequestInner").fields()).extracting("name").containsExactly("x");
                }

                @Test
                void req3_5_crossSchemaImportWithoutLocationResolves() {
                        ParseResult r = parse(Wsdls.wsdl()
                                        .types("""
                                                        <xs:import namespace="urn:common"/>
                                                        <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                                                          <xs:element name="money" type="c:Money" xmlns:c="urn:common"/>
                                                        </xs:sequence></xs:complexType></xs:element>""" + RESPONSE)
                                        .extraSchemas("""
                                                        <xs:schema targetNamespace="urn:common" elementFormDefault="qualified">
                                                          <xs:complexType name="Money"><xs:sequence>
                                                            <xs:element name="amount" type="xs:decimal"/>
                                                          </xs:sequence></xs:complexType>
                                                        </xs:schema>""")
                                        .build());
                        assertThat(codes(r)).containsExactly("MULTIPLE_NAMESPACES");
                        assertThat(r.model().type("Money").fields().get(0))
                                        .isEqualTo(new FieldModel("amount", "urn:common", "xs:decimal", 1, 1));
                }

                @Test
                void req3_7_unsupportedConstructsWarnAndKeepTheRest() {
                        ParseResult r = withTypes(
                                        """
                                                        <xs:element name="OpRequest"><xs:complexType>
                                                          <xs:sequence>
                                                            <xs:element name="keep" type="xs:string"/>
                                                            <xs:choice><xs:element name="a" type="xs:string"/><xs:element name="b" type="xs:int"/></xs:choice>
                                                            <xs:any processContents="lax"/>
                                                            <xs:element name="n" type="xs:string" nillable="true"/>
                                                          </xs:sequence>
                                                          <xs:attribute name="version" type="xs:string"/>
                                                        </xs:complexType></xs:element>"""
                                                        + RESPONSE);
                        assertThat(codes(r)).containsExactlyInAnyOrder("XSD_ATTRIBUTE", "XSD_CHOICE", "XSD_ANY",
                                        "XSD_NILLABLE");
                        assertThat(r.model().type("OpRequest").fields()).extracting("name").containsExactly("keep",
                                        "n");
                        assertThat(r.warnings()).filteredOn(w -> w.code().equals("XSD_CHOICE"))
                                        .first().extracting(Warning::location).asString()
                                        .endsWith("type[OpRequest]/choice");
                }

                @Test
                void req3_7_derivationAllListUnion() {
                        ParseResult r = withTypes(
                                        """
                                                        <xs:complexType name="Base"><xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence></xs:complexType>
                                                        <xs:complexType name="Derived"><xs:complexContent><xs:extension base="tns:Base"/></xs:complexContent></xs:complexType>
                                                        <xs:complexType name="Unordered"><xs:all><xs:element name="z" type="xs:string"/></xs:all></xs:complexType>
                                                        <xs:simpleType name="Codes"><xs:list itemType="xs:string"/></xs:simpleType>
                                                        <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                                                          <xs:element name="d" type="tns:Derived"/><xs:element name="u" type="tns:Unordered"/>
                                                          <xs:element name="c" type="tns:Codes"/>
                                                        </xs:sequence></xs:complexType></xs:element>"""
                                                        + RESPONSE);
                        assertThat(codes(r)).containsExactly("XSD_DERIVATION", "XSD_ALL", "XSD_LIST_UNION");
                        assertThat(r.model().type("Codes"))
                                        .isEqualTo(TypeModel.simple("Codes", BuiltIn.STRING, List.of()));
                }

                @Test
                void req3_11_duplicateFieldNamesKeepFirst() {
                        ParseResult r = withTypes("""
                                        <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                                          <xs:element name="a" type="xs:string"/><xs:element name="b" type="xs:int"/>
                                          <xs:element name="a" type="xs:int"/>
                                        </xs:sequence></xs:complexType></xs:element>""" + RESPONSE);
                        assertThat(codes(r)).containsExactly("XSD_DUPLICATE_FIELD");
                        assertThat(r.model().type("OpRequest").fields()).extracting("name", "typeId")
                                        .containsExactly(org.assertj.core.groups.Tuple.tuple("a", "xs:string"),
                                                        org.assertj.core.groups.Tuple.tuple("b", "xs:int"));
                }

                @Test
                void req3_8_unresolvedTypeBecomesString() {
                        ParseResult r = withTypes("""
                                        <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                                          <xs:element name="x" type="tns:Missing"/>
                                        </xs:sequence></xs:complexType></xs:element>""" + RESPONSE);
                        assertThat(codes(r)).containsExactly("UNRESOLVED_TYPE");
                        assertThat(r.model().type("OpRequest").fields().get(0).typeId()).isEqualTo("xs:string");
                }

                @Test
                void req3_10_recursiveAndCollidingTypesGetUniqueIds() {
                        ParseResult r = withTypes(
                                        """
                                                        <xs:complexType name="Node"><xs:sequence>
                                                          <xs:element name="child" type="tns:Node" minOccurs="0" maxOccurs="unbounded"/>
                                                        </xs:sequence></xs:complexType>
                                                        <xs:complexType name="OpRequest"><xs:sequence><xs:element name="n" type="tns:Node"/></xs:sequence></xs:complexType>
                                                        <xs:element name="OpRequest" type="tns:OpRequest"/>"""
                                                        + RESPONSE);
                        assertThat(r.model().type("Node").fields().get(0).typeId()).isEqualTo("Node");
                        assertThat(r.model().operations().get(0).input().typeId()).isEqualTo("OpRequest");
                        assertThat(r.model().types().keySet()).doesNotHaveDuplicates();
                }
        }
}
