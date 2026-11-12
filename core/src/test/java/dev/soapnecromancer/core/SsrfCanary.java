package dev.soapnecromancer.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSRF canary: a localhost HTTP server that records every request, plus a WSDL that points every
 * fetch vector at it (and at the cloud metadata address). A safe generator produces zero hits.
 * Exported in the core test-jar so the web module's upload test (Task 10) reuses the same payload.
 */
public final class SsrfCanary implements AutoCloseable {
    public static final String METADATA = "http://169.254.169.254/latest/meta-data/";

    private final HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    public SsrfCanary() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            hits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] body = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + path;
    }

    public List<String> hits() {
        return List.copyOf(hits);
    }

    /** A valid one-operation WSDL whose wsdl:import, xs:import, xs:include and xs:redefine all point outward. */
    public byte[] wsdl() {
        String xml = """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:s="http://schemas.xmlsoap.org/wsdl/soap/"
                    xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:t" targetNamespace="urn:t">
                  <wsdl:import namespace="urn:w1" location="%s"/>
                  <wsdl:import namespace="urn:w2" location="%s"/>
                  <wsdl:types>
                    <xs:schema targetNamespace="urn:t" elementFormDefault="qualified">
                      <xs:import namespace="urn:x1" schemaLocation="%s"/>
                      <xs:import namespace="urn:x2" schemaLocation="%s"/>
                      <xs:include schemaLocation="%s"/>
                      <xs:redefine schemaLocation="%s"/>
                      <xs:element name="OpRequest"><xs:complexType><xs:sequence>
                        <xs:element name="id" type="xs:string"/>
                      </xs:sequence></xs:complexType></xs:element>
                      <xs:element name="OpResponse"><xs:complexType><xs:sequence>
                        <xs:element name="ok" type="xs:boolean"/>
                      </xs:sequence></xs:complexType></xs:element>
                    </xs:schema>
                  </wsdl:types>
                  <wsdl:message name="In"><wsdl:part name="p" element="tns:OpRequest"/></wsdl:message>
                  <wsdl:message name="Out"><wsdl:part name="p" element="tns:OpResponse"/></wsdl:message>
                  <wsdl:portType name="PT"><wsdl:operation name="Op">
                    <wsdl:input message="tns:In"/><wsdl:output message="tns:Out"/>
                  </wsdl:operation></wsdl:portType>
                  <wsdl:binding name="B" type="tns:PT">
                    <s:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="Op"><s:operation soapAction="urn:t:Op"/>
                      <wsdl:input><s:body use="literal"/></wsdl:input><wsdl:output><s:body use="literal"/></wsdl:output>
                    </wsdl:operation>
                  </wsdl:binding>
                  <wsdl:service name="S"><wsdl:port name="P" binding="tns:B">
                    <s:address location="http://legacy.example/ws"/>
                  </wsdl:port></wsdl:service>
                </wsdl:definitions>
                """.formatted(url("wsdl-import.wsdl"), METADATA + "wsdl", url("xs-import.xsd"), METADATA + "xsd",
                url("xs-include.xsd"), url("xs-redefine.xsd"));
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
