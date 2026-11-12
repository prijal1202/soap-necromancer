package dev.soapnecromancer.mockbank;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * The "decades-old" bank: SOAP 1.1, document/literal, at {@value #PATH}.
 * GET {@value #PATH}?wsdl returns bank.wsdl with soap:address pointing at this
 * host.
 */
@RestController
@RequestMapping(MockBankEndpoint.PATH)
public class MockBankEndpoint {
    public static final String PATH = "/mock-bank/ws";
    private static final MediaType SOAP_11 = MediaType.parseMediaType("text/xml;charset=UTF-8");
    private static final String WSDL_DEFAULT_ADDRESS = "http://localhost:8080/mock-bank/ws";

    private final BankLedger ledger;
    private final String publicBaseUrl;
    private final String wsdlTemplate;

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
    }

    @GetMapping(params = "wsdl", produces = MediaType.TEXT_XML_VALUE)
    public String wsdl() {
        String address = publicBaseUrl.isEmpty()
                ? ServletUriComponentsBuilder.fromCurrentContextPath().path(PATH).toUriString()
                : publicBaseUrl + PATH;
        // Forwarded headers come from the client: escape so they cannot break out of
        // the attribute.
        String attr = address.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
        return wsdlTemplate.replace(WSDL_DEFAULT_ADDRESS, attr);
    }

    @PostMapping
    public ResponseEntity<String> invoke(@RequestBody byte[] envelope) {
        try {
            Element payload = SoapXml.bodyPayload(envelope);
            if (!SoapXml.BANK_NS.equals(payload.getNamespaceURI())) {
                throw new MockBankFault("soap:Client", "Unknown namespace: " + payload.getNamespaceURI());
            }
            Document out = SoapXml.newBuilder().newDocument();
            Element body = SoapXml.newEnvelopeBody(out);
            switch (payload.getLocalName()) {
                case "GetBalanceRequest" -> getBalance(payload, body);
                case "TransferRequest" -> transfer(payload, body);
                case "GetStatementRequest" -> getStatement(payload, body);
                default -> throw new MockBankFault("soap:Client", "Unknown operation: " + payload.getLocalName());
            }
            return ResponseEntity.ok().contentType(SOAP_11).body(SoapXml.serialize(out));
        } catch (MockBankFault f) {
            // SOAP 1.1 mandates HTTP 500 for faults.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(SOAP_11)
                    .body(SoapXml.fault(f.faultCode(), f.getMessage()));
        }
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
        BigDecimal amount;
        try {
            amount = new BigDecimal(SoapXml.text(req, "amount", true));
        } catch (NumberFormatException e) {
            throw new MockBankFault("soap:Client", "amount is not a valid xs:decimal");
        }
        var result = ledger.transfer(SoapXml.text(req, "fromAccountId", true),
                SoapXml.text(req, "toAccountId", true), amount, SoapXml.text(req, "memo", false));
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
            throw new MockBankFault("soap:Client", "maxLines is not a valid xs:int");
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
