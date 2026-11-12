package dev.soapnecromancer.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Parser Requirement 5: hostile or huge WSDLs fail fast with a named limit, never a crash. */
class ParserLimitsTest {
    private static final String RESPONSE = """
            <xs:element name="OpResponse"><xs:complexType><xs:sequence>
              <xs:element name="ok" type="xs:boolean"/>
            </xs:sequence></xs:complexType></xs:element>""";

    private static ParseResult parse(byte[] wsdl) {
        return new WsdlParser().parse(wsdl);
    }

    /** T0 -> T1 -> ... -> Tn through optional fields: valid XSD, shallow XML, deep type graph. */
    private static byte[] chain(int n) {
        StringBuilder types = new StringBuilder("<xs:element name=\"OpRequest\" type=\"tns:T0\"/>").append(RESPONSE);
        for (int i = 0; i < n; i++) {
            types.append("<xs:complexType name=\"T").append(i).append("\"><xs:sequence><xs:element name=\"n\" type=\"tns:T")
                    .append(i + 1).append("\" minOccurs=\"0\"/></xs:sequence></xs:complexType>");
        }
        types.append("<xs:complexType name=\"T").append(n).append("\"><xs:sequence/></xs:complexType>");
        return Wsdls.wsdl().types(types.toString()).build();
    }

    private static WsdlParseException fatal(Runnable r) {
        try {
            r.run();
        } catch (WsdlParseException e) {
            return e;
        }
        throw new AssertionError("expected a WsdlParseException");
    }

    @Test
    void req5_1_deepXmlIsRejectedBeforeSchemaReading() {
        int n = SecureXml.MAX_ELEMENT_DEPTH + 50;
        String deep = "<a>".repeat(n) + "</a>".repeat(n);
        WsdlParseException e = fatal(() -> parse(deep.getBytes(StandardCharsets.UTF_8)));
        assertThat(e.getMessage()).contains(String.valueOf(SecureXml.MAX_ELEMENT_DEPTH));
    }

    @Test
    void req5_1_nestingUnderTheLimitStillParses() {
        // A WSDL inside the depth limit is unaffected: the bank WSDL nests about 8 levels.
        assertThat(parse(Wsdls.resource("bank.wsdl")).model().operations()).hasSize(3);
    }

    @Test
    void req5_2_tooManyOperations() {
        WsdlParseException e = fatal(() -> new WsdlParser(new WsdlParser.Limits(2, 1000, 64))
                .parse(Wsdls.resource("bank.wsdl")));
        assertThat(e.warnings()).extracting(Warning::code).contains("LIMIT_OPERATIONS");
        assertThat(e.getMessage()).contains("3 > 2");
    }

    @Test
    void req5_3_tooManyTypes() {
        WsdlParseException e = fatal(() -> new WsdlParser(new WsdlParser.Limits(200, 5, 64))
                .parse(Wsdls.resource("bank.wsdl")));
        assertThat(e.warnings()).extracting(Warning::code).contains("LIMIT_TYPES");
    }

    @Test
    void req5_4_deepTypeChainIsRejectedButModerateChainsWork() {
        assertThat(parse(chain(40)).model().types()).containsKey("T40");
        WsdlParseException e = fatal(() -> parse(chain(100)));
        assertThat(e.warnings()).extracting(Warning::code).contains("LIMIT_DEPTH");
    }

    /** The point of checking during the walk: a 5,000-type chain on Render's 512 KB stack fails cleanly. */
    @Test
    void req5_4_longChainCannotOverflowA512kStack() throws Exception {
        byte[] wsdl = chain(5000);
        assertThat(wsdl.length).isLessThan(2 * 1024 * 1024);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                parse(wsdl);
            } catch (Throwable e) {
                outcome.set(e);
            }
        }, "parse-512k", 512 * 1024);
        t.start();
        t.join(60_000);
        assertThat(outcome.get()).isInstanceOf(WsdlParseException.class);
        assertThat(((WsdlParseException) outcome.get()).warnings()).extracting(Warning::code).contains("LIMIT_DEPTH");
    }

    @Test
    void req5_5_requiredSelfReferenceIsUnsatisfiable() {
        WsdlParseException e = fatal(() -> parse(Wsdls.wsdl().types("""
                <xs:complexType name="Node"><xs:sequence>
                  <xs:element name="next" type="tns:Node"/>
                </xs:sequence></xs:complexType>
                <xs:element name="OpRequest" type="tns:Node"/>""" + RESPONSE).build()));
        assertThat(e.warnings()).extracting(Warning::code).contains("UNSATISFIABLE_RECURSION");
        assertThat(e.getMessage()).contains("Node -> Node");
    }

    @Test
    void req5_5_requiredMutualRecursionIsUnsatisfiable() {
        WsdlParseException e = fatal(() -> parse(Wsdls.wsdl().types("""
                <xs:complexType name="A"><xs:sequence><xs:element name="b" type="tns:B"/></xs:sequence></xs:complexType>
                <xs:complexType name="B"><xs:sequence>
                  <xs:element name="a" type="tns:A" minOccurs="1" maxOccurs="unbounded"/>
                </xs:sequence></xs:complexType>
                <xs:element name="OpRequest" type="tns:A"/>""" + RESPONSE).build()));
        assertThat(e.getMessage()).contains("A -> B -> A");
    }

    @Test
    void req5_6_optionalRecursionStaysSupported() {
        ParseResult r = parse(Wsdls.wsdl().types("""
                <xs:complexType name="Node"><xs:sequence>
                  <xs:element name="label" type="xs:string"/>
                  <xs:element name="child" type="tns:Node" minOccurs="0" maxOccurs="unbounded"/>
                  <xs:element name="parent" type="tns:Node" minOccurs="0"/>
                </xs:sequence></xs:complexType>
                <xs:element name="OpRequest" type="tns:Node"/>""" + RESPONSE).build());
        assertThat(r.warnings()).isEmpty();
        assertThat(r.model().type("Node").fields()).hasSize(3);
    }
}
