package dev.soapnecromancer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Test fixtures: the shared bank.wsdl and a template for small one-operation WSDLs. */
final class Wsdls {
    private Wsdls() {}

    static byte[] resource(String name) {
        try (InputStream in = Wsdls.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new IllegalArgumentException("Missing test resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(String name) {
        return new String(resource(name), StandardCharsets.UTF_8);
    }

    /** Defaults produce a valid document/literal SOAP 1.1 WSDL with operation "Op". */
    static final class Builder {
        String types = """
                <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                  <xs:element name="id" type="xs:string"/>
                </xs:sequence></xs:complexType></xs:element>
                <xs:element name="OpResponse"><xs:complexType><xs:sequence>
                  <xs:element name="ok" type="xs:boolean"/>
                </xs:sequence></xs:complexType></xs:element>""";
        String extraSchemas = "";
        String bindingNs = "http://schemas.xmlsoap.org/wsdl/soap/";
        String style = "document";
        String use = "literal";
        String inputExtra = "";
        String extraRoot = "";
        String inputParts = "<wsdl:part name=\"p\" element=\"tns:OpRequest\"/>";
        boolean output = true;

        Builder types(String t) { types = t; return this; }
        Builder extraSchemas(String s) { extraSchemas = s; return this; }
        Builder soap12() { bindingNs = "http://schemas.xmlsoap.org/wsdl/soap12/"; return this; }
        Builder style(String s) { style = s; return this; }
        Builder use(String u) { use = u; return this; }
        Builder inputExtra(String x) { inputExtra = x; return this; }
        Builder extraRoot(String x) { extraRoot = x; return this; }
        Builder inputParts(String p) { inputParts = p; return this; }
        Builder oneWay() { output = false; return this; }

        byte[] build() {
            String out = output ? "<wsdl:output message=\"tns:OpResponse\"/>" : "";
            String bout = output ? "<wsdl:output><s:body use=\"" + use + "\"/></wsdl:output>" : "";
            String xml = """
                    <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:s="%s"
                        xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:t" targetNamespace="urn:t">
                      %s
                      <wsdl:types>
                        <xs:schema targetNamespace="urn:t" elementFormDefault="qualified">%s</xs:schema>
                        %s
                      </wsdl:types>
                      <wsdl:message name="OpRequest">%s</wsdl:message>
                      <wsdl:message name="OpResponse"><wsdl:part name="p" element="tns:OpResponse"/></wsdl:message>
                      <wsdl:portType name="PT"><wsdl:operation name="Op">
                        <wsdl:input message="tns:OpRequest"/>%s
                      </wsdl:operation></wsdl:portType>
                      <wsdl:binding name="B" type="tns:PT">
                        <s:binding style="%s" transport="http://schemas.xmlsoap.org/soap/http"/>
                        <wsdl:operation name="Op"><s:operation soapAction="urn:t:Op"/>
                          <wsdl:input><s:body use="%s"/>%s</wsdl:input>%s
                        </wsdl:operation>
                      </wsdl:binding>
                      <wsdl:service name="S"><wsdl:port name="P" binding="tns:B">
                        <s:address location="http://legacy.example/ws"/>
                      </wsdl:port></wsdl:service>
                    </wsdl:definitions>
                    """.formatted(bindingNs, extraRoot, types, extraSchemas, inputParts, out, style, use, inputExtra, bout);
            return xml.getBytes(StandardCharsets.UTF_8);
        }
    }

    static Builder wsdl() {
        return new Builder();
    }
}
