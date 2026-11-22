package dev.wsdlforge.core;

import dev.wsdlforge.runtime.model.BuiltIn;
import dev.wsdlforge.runtime.model.FieldModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parser Requirements 3.1, 3.14 and 3.15: type extension is flattened, integer types keep their width. */
@Tag("fast")
class XsdExtensionTest {

    private static ParseResult parse(String types) {
        String xml = """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:s="http://schemas.xmlsoap.org/wsdl/soap/"
                    xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:t" targetNamespace="urn:t">
                  <wsdl:types><xs:schema targetNamespace="urn:t" elementFormDefault="qualified">%s
                    <xs:element name="Res"><xs:complexType><xs:sequence><xs:element name="ok" type="xs:boolean"/></xs:sequence></xs:complexType></xs:element>
                  </xs:schema></wsdl:types>
                  <wsdl:message name="In"><wsdl:part name="p" element="tns:Req"/></wsdl:message>
                  <wsdl:message name="Out"><wsdl:part name="p" element="tns:Res"/></wsdl:message>
                  <wsdl:portType name="PT"><wsdl:operation name="Op"><wsdl:input message="tns:In"/><wsdl:output message="tns:Out"/></wsdl:operation></wsdl:portType>
                  <wsdl:binding name="B" type="tns:PT"><s:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="Op"><s:operation soapAction="urn:t:Op"/><wsdl:input><s:body use="literal"/></wsdl:input><wsdl:output><s:body use="literal"/></wsdl:output></wsdl:operation>
                  </wsdl:binding>
                  <wsdl:service name="S"><wsdl:port name="P" binding="tns:B"><s:address location="http://legacy.example/ws"/></wsdl:port></wsdl:service>
                </wsdl:definitions>""".formatted(types);
        return new WsdlParser().parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> names(ParseResult r, String typeId) {
        return r.model().type(typeId).fields().stream().map(FieldModel::name).toList();
    }

    @Test
    void req3_14_extensionPutsBaseFieldsFirst() {
        ParseResult r = parse("""
                <xs:complexType name="Base"><xs:sequence>
                  <xs:element name="id" type="xs:string"/><xs:element name="note" type="xs:string" minOccurs="0"/>
                </xs:sequence></xs:complexType>
                <xs:complexType name="Derived"><xs:complexContent><xs:extension base="tns:Base"><xs:sequence>
                  <xs:element name="count" type="xs:int"/>
                </xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                <xs:element name="Req" type="tns:Derived"/>""");
        assertThat(r.warnings()).isEmpty();
        assertThat(names(r, "Derived")).containsExactly("id", "note", "count");
        // Occurrence and type of the inherited fields are kept.
        FieldModel note = r.model().type("Derived").fields().get(1);
        assertThat(note.minOccurs()).isZero();
        assertThat(note.typeId()).isEqualTo("xs:string");
    }

    @Test
    void req3_14_chainOfExtensionsFlattensEveryLevel() {
        ParseResult r = parse("""
                <xs:complexType name="A"><xs:sequence><xs:element name="a" type="xs:string"/></xs:sequence></xs:complexType>
                <xs:complexType name="B"><xs:complexContent><xs:extension base="tns:A"><xs:sequence>
                  <xs:element name="b" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                <xs:complexType name="C"><xs:complexContent><xs:extension base="tns:B"><xs:sequence>
                  <xs:element name="c" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                <xs:element name="Req" type="tns:C"/>""");
        assertThat(r.warnings()).isEmpty();
        assertThat(names(r, "C")).containsExactly("a", "b", "c");
    }

    @Test
    void req3_14_extensionWithNoOwnFieldsInheritsEverything() {
        ParseResult r = parse("""
                <xs:complexType name="Base"><xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence></xs:complexType>
                <xs:complexType name="Same"><xs:complexContent><xs:extension base="tns:Base"/></xs:complexContent></xs:complexType>
                <xs:element name="Req" type="tns:Same"/>""");
        assertThat(r.warnings()).isEmpty();
        assertThat(names(r, "Same")).containsExactly("id");
    }

    @Test
    void req3_14_anonymousExtensionInsideAnElement() {
        ParseResult r = parse("""
                <xs:complexType name="Base"><xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence></xs:complexType>
                <xs:element name="Req"><xs:complexType><xs:complexContent><xs:extension base="tns:Base"><xs:sequence>
                  <xs:element name="extra" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType></xs:element>""");
        assertThat(r.warnings()).isEmpty();
        assertThat(names(r, "Req")).containsExactly("id", "extra");
    }

    @Test
    void req3_14_extendingAnyTypeAddsNothing() {
        ParseResult r = parse("""
                <xs:complexType name="T"><xs:complexContent><xs:extension base="xs:anyType"><xs:sequence>
                  <xs:element name="x" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                <xs:element name="Req" type="tns:T"/>""");
        assertThat(r.warnings()).isEmpty();
        assertThat(names(r, "T")).containsExactly("x");
    }

    @Test
    void req3_15_aTypeThatExtendsItselfKeepsItsOwnFieldsAndWarns() {
        ParseResult r = parse("""
                <xs:complexType name="Loop"><xs:complexContent><xs:extension base="tns:Loop"><xs:sequence>
                  <xs:element name="x" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                <xs:element name="Req" type="tns:Loop"/>""");
        assertThat(r.warnings()).extracting(Warning::code).containsExactly("XSD_DERIVATION");
        assertThat(r.warnings().get(0).message()).contains("Loop");
        assertThat(names(r, "Loop")).containsExactly("x");
    }

    @Test
    void req3_15_extendingASimpleTypeWarns() {
        ParseResult r = parse("""
                <xs:complexType name="T"><xs:complexContent><xs:extension base="xs:string"><xs:sequence>
                  <xs:element name="x" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                <xs:element name="Req" type="tns:T"/>""");
        assertThat(r.warnings()).extracting(Warning::code).contains("XSD_DERIVATION");
        assertThat(names(r, "T")).containsExactly("x");
    }

    @Test
    void req3_1_integerFamilyKeepsItsWidth() {
        ParseResult r = parse("""
                <xs:element name="Req"><xs:complexType><xs:sequence>
                  <xs:element name="a" type="xs:integer"/><xs:element name="b" type="xs:positiveInteger"/>
                  <xs:element name="c" type="xs:unsignedLong"/><xs:element name="d" type="xs:short"/>
                  <xs:element name="e" type="xs:byte"/><xs:element name="f" type="xs:unsignedInt"/>
                  <xs:element name="g" type="xs:unsignedShort"/><xs:element name="h" type="xs:nonNegativeInteger"/>
                </xs:sequence></xs:complexType></xs:element>""");
        assertThat(r.warnings()).isEmpty();
        assertThat(r.model().type("Req").fields()).extracting(FieldModel::typeId)
                .containsExactly("xs:integer", "xs:integer", "xs:integer", "xs:int", "xs:int", "xs:long", "xs:int",
                        "xs:integer");
        assertThat(r.model().type("xs:integer").base()).isEqualTo(BuiltIn.INTEGER);
    }

    @Test
    void req3_2_floatAndDoubleStayStringsWithAWarning() {
        ParseResult r = parse("""
                <xs:element name="Req"><xs:complexType><xs:sequence>
                  <xs:element name="d" type="xs:double"/><xs:element name="f" type="xs:float"/>
                </xs:sequence></xs:complexType></xs:element>""");
        assertThat(r.warnings()).extracting(Warning::code).containsExactly("UNSUPPORTED_BUILTIN", "UNSUPPORTED_BUILTIN");
        assertThat(r.model().type("Req").fields()).extracting(FieldModel::typeId)
                .containsExactly("xs:string", "xs:string");
    }
}
