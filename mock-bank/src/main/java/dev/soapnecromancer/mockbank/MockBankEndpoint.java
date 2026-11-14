package dev.soapnecromancer.mockbank;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The "decades-old" bank, document/literal: SOAP 1.1 at {@value #PATH}, SOAP
 * 1.2 at {@value #PATH_12}, one shared ledger. GET {@code <path>?wsdl} returns
 * the WSDL for that path with soap:address pointing at this host. Public on the
 * hosted demo, so every request is bounded in size, and every failure is
 * answered as a SOAP fault a SOAP client can read.
 */
@RestController
public class MockBankEndpoint {
    public static final String PATH = "/mock-bank/ws";
    public static final String PATH_12 = "/mock-bank/ws12";
    /**
     * Bank requests are a few hundred bytes; this leaves room for verbose clients.
     */
    public static final int MAX_ENVELOPE_BYTES = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(MockBankEndpoint.class);
    private static final String WSDL_DEFAULT_LOCATION = "location=\"http://localhost:8080/mock-bank/ws\"";
    /**
     * xs:decimal lexical space: no exponent, which also keeps BigDecimal arithmetic
     * cheap.
     */
    private static final Pattern XS_DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    private final BankLedger ledger;
    private final String publicBaseUrl;
    private final String wsdlTemplate;
    private final String wsdl12Template;

    /**
     * @param publicBaseUrl public origin of this app, e.g.
     *                      {@code https://soap-necromancer.onrender.com}.
     *                      When blank, the address is derived from the request
     *                      (forwarded headers included).
     */
    public MockBankEndpoint(BankLedger ledger, @Value("${mockbank.public-base-url:}") String publicBaseUrl)
            throws IOException {
        this.ledger = ledger;
        this.publicBaseUrl = publicBaseUrl.strip().replaceAll("/+$", "");
        this.wsdlTemplate = new ClassPathResource("bank.wsdl").getContentAsString(StandardCharsets.UTF_8);
        this.wsdl12Template = soap12(wsdlTemplate);
    }

    /**
     * The SOAP 1.2 WSDL is derived from bank.wsdl, which stays SOAP 1.1 because it
     * is the golden test fixture. Only the WSDL SOAP binding namespace and the
     * header comment change; soapAction and the transport URI are the same in
     * both bindings.
     */
    static String soap12(String wsdl) {
        return requireReplace(
                requireReplace(wsdl, "xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\"",
                        "xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap12/\""),
                "Legacy Bank Service, SOAP 1.1 document/literal.",
                "Legacy Bank Service, SOAP 1.2 document/literal (derived from bank.wsdl).");
    }

    private static String requireReplace(String s, String target, String replacement) {
        if (!s.contains(target))
            throw new IllegalStateException("bank.wsdl no longer contains: " + target);
        return s.replace(target, replacement);
    }

    /**
     * bank.wsdl (SOAP 1.1) with this host's address. Not cacheable: the address
     * can depend on request headers.
     */
    public String wsdl() {
        return withAddress(wsdlTemplate, PATH);
    }

    /** The SOAP 1.2 variant of {@link #wsdl()}, addressed at {@value #PATH_12}. */
    public String wsdl12() {
        return withAddress(wsdl12Template, PATH_12);
    }

    private String withAddress(String template, String path) {
        String address = publicBaseUrl.isEmpty()
                ? ServletUriComponentsBuilder.fromCurrentContextPath().path(path).toUriString()
                : publicBaseUrl + path;
        // Forwarded headers come from the client: escape so they cannot break out of
        // the attribute.
        String attr = address.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
        return template.replace(WSDL_DEFAULT_LOCATION, "location=\"" + attr + "\"");
    }

    @GetMapping(path = PATH, params = "wsdl", produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> getWsdl() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wsdl());
    }

    @GetMapping(path = PATH_12, params = "wsdl", produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> getWsdl12() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wsdl12());
    }

    @PostMapping(PATH)
    public ResponseEntity<String> invoke(HttpServletRequest request) {
        return handle(SoapVersion.SOAP_11, request);
    }

    @PostMapping(PATH_12)
    public ResponseEntity<String> invoke12(HttpServletRequest request) {
        return handle(SoapVersion.SOAP_12, request);
    }

    private ResponseEntity<String> handle(SoapVersion version, HttpServletRequest request) {
        try {
            Element payload = SoapXml.bodyPayload(readEnvelope(request), version);
            if (!SoapXml.BANK_NS.equals(payload.getNamespaceURI())) {
                throw MockBankFault.sender("Unknown namespace: " + BankLedger.shorten(String.valueOf(
                        payload.getNamespaceURI())));
            }
            Document out = SoapXml.newBuilder().newDocument();
            Element body = SoapXml.newEnvelopeBody(out, version);
            switch (payload.getLocalName()) {
                case "GetBalanceRequest" -> getBalance(payload, body);
                case "TransferRequest" -> transfer(payload, body);
                case "GetStatementRequest" -> getStatement(payload, body);
                default -> throw MockBankFault.sender("Unknown operation: "
                        + BankLedger.shorten(payload.getLocalName()));
            }
            return ResponseEntity.ok().contentType(version.mediaType()).body(SoapXml.serialize(out));
        } catch (MockBankFault f) {
            return fault(version, f.code(), f.getMessage());
        } catch (RuntimeException e) {
            // A SOAP client cannot read Spring's JSON error page; answer with a Server
            // fault instead.
            log.error("mock-bank request failed", e);
            return fault(version, MockBankFault.Code.RECEIVER, "Internal error in the bank");
        }
    }

    /**
     * SOAP 1.1 answers every fault with HTTP 500. SOAP 1.2 (Part 2, 7.5.1.2) uses
     * 400 for Sender and 500 for the rest. A VersionMismatch is always a SOAP 1.1
     * envelope (SOAP 1.2 Part 1, Appendix A), so a 1.1 client can read it.
     */
    private static ResponseEntity<String> fault(SoapVersion version, MockBankFault.Code code, String message) {
        SoapVersion in = code == MockBankFault.Code.VERSION_MISMATCH ? SoapVersion.SOAP_11 : version;
        HttpStatus status = in == SoapVersion.SOAP_12 && code == MockBankFault.Code.SENDER
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).contentType(in.mediaType())
                .body(SoapXml.fault(in, code, message));
    }

    /** Reads at most {@link #MAX_ENVELOPE_BYTES}; nothing larger is buffered. */
    private static byte[] readEnvelope(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_ENVELOPE_BYTES)
            throw tooLarge();
        try (InputStream in = request.getInputStream()) {
            byte[] data = in.readNBytes(MAX_ENVELOPE_BYTES + 1);
            if (data.length > MAX_ENVELOPE_BYTES)
                throw tooLarge();
            if (data.length == 0)
                throw MockBankFault.sender("Empty request: send a SOAP envelope");
            return data;
        } catch (IOException e) {
            throw MockBankFault.sender("The request body could not be read");
        }
    }

    private static MockBankFault tooLarge() {
        return MockBankFault.sender("The SOAP envelope is larger than " + MAX_ENVELOPE_BYTES + " bytes");
    }

    private void getBalance(Element req, Element body) {
        var acct = ledger.account(SoapXml.text(req, "accountId", true));
        Element resp = SoapXml.add(body, "GetBalanceResponse", null);
        SoapXml.add(resp, "accountId", acct.id());
        SoapXml.add(resp, "balance", acct.balance().toPlainString());
        SoapXml.add(resp, "currency", acct.currency());
        SoapXml.add(resp, "overdraftAllowed", acct.overdraftAllowed());
        SoapXml.add(resp, "asOf", ledger.now());
    }

    private void transfer(Element req, Element body) {
        String text = SoapXml.text(req, "amount", true);
        if (!XS_DECIMAL.matcher(text).matches()) {
            throw MockBankFault.sender("amount is not a valid xs:decimal");
        }
        var result = ledger.transfer(SoapXml.text(req, "fromAccountId", true),
                SoapXml.text(req, "toAccountId", true), new BigDecimal(text), SoapXml.text(req, "memo", false));
        Element resp = SoapXml.add(body, "TransferResponse", null);
        SoapXml.add(resp, "transactionId", result.transactionId());
        SoapXml.add(resp, "newBalance", result.newBalance().toPlainString());
        SoapXml.add(resp, "executedAt", result.executedAt());
    }

    private void getStatement(Element req, Element body) {
        String id = SoapXml.text(req, "accountId", true);
        String max = SoapXml.text(req, "maxLines", false);
        Integer maxLines;
        try {
            maxLines = max == null ? null : Integer.valueOf(max);
        } catch (NumberFormatException e) {
            throw MockBankFault.sender("maxLines is not a valid xs:int");
        }
        var lines = ledger.statement(id, maxLines);
        Element resp = SoapXml.add(body, "GetStatementResponse", null);
        SoapXml.add(resp, "accountId", id);
        for (var l : lines) {
            Element line = SoapXml.add(resp, "line", null);
            SoapXml.add(line, "transactionId", l.transactionId());
            SoapXml.add(line, "date", l.date());
            SoapXml.add(line, "type", l.type());
            SoapXml.add(line, "amount", l.amount().toPlainString());
            if (l.description() != null)
                SoapXml.add(line, "description", l.description());
        }
    }
}
