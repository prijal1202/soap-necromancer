package dev.soapnecromancer.mockbank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The SOAP 1.2 endpoint at {@value MockBankEndpoint#PATH_12}, sharing the
 * ledger with SOAP 1.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockBankSoap12Test {
    private static final String SOAP11_ENV = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP12_ENV = "http://www.w3.org/2003/05/soap-envelope";
    /** The {@code xml} prefix is predefined in every namespace context. */
    private static final Map<String, String> NS = Map.of("s", SOAP12_ENV, "b", "urn:example:bank:v1");
    private static final String SOAP12_MEDIA = "application/soap+xml; charset=utf-8; action=\"urn:example:bank:v1:GetBalance\"";

    @Autowired
    MockMvc mvc;
    @Autowired
    BankLedger ledger;

    @BeforeEach
    void reset() {
        ledger.reset();
    }

    private ResultActions send(String path, String envelopeNs, String payload) throws Exception {
        String env = """
                <soap:Envelope xmlns:soap="%s" xmlns:b="urn:example:bank:v1">
                  <soap:Body>%s</soap:Body>
                </soap:Envelope>""".formatted(envelopeNs, payload);
        String type = envelopeNs.equals(SOAP12_ENV) ? SOAP12_MEDIA : "text/xml";
        return mvc.perform(post(path).contentType(type).content(env));
    }

    private ResultActions soap12(String payload) throws Exception {
        return send(MockBankEndpoint.PATH_12, SOAP12_ENV, payload);
    }

    @Test
    void getBalanceAnswersInSoap12() throws Exception {
        soap12("<b:GetBalanceRequest><b:accountId>ACC-1001</b:accountId></b:GetBalanceRequest>")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/soap+xml"))
                .andExpect(xpath("/s:Envelope/s:Body/b:GetBalanceResponse/b:balance", NS).string("2500.00"));
    }

    /** SOAP 1.2 Part 2, 7.5.1.2: a Sender fault is HTTP 400. */
    @Test
    void senderFaultIsCodeValueReasonTextAnd400() throws Exception {
        soap12("<b:GetBalanceRequest><b:accountId>NOPE</b:accountId></b:GetBalanceRequest>")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/soap+xml"))
                .andExpect(xpath("/s:Envelope/s:Body/s:Fault/s:Code/s:Value", NS).string("soap:Sender"))
                .andExpect(xpath("/s:Envelope/s:Body/s:Fault/s:Reason/s:Text", NS).string("Unknown account: NOPE"))
                .andExpect(xpath("//s:Text/@xml:lang", NS).string("en"))
                .andExpect(xpath("//faultcode").doesNotExist());
    }

    @Test
    void malformedEnvelopeIsASenderFault() throws Exception {
        mvc.perform(post(MockBankEndpoint.PATH_12).contentType(SOAP12_MEDIA).content("<not-closed"))
                .andExpect(status().isBadRequest())
                .andExpect(xpath("//*[local-name()='Value']").string("soap:Sender"));
    }

    /**
     * SOAP 1.2 Part 1, Appendix A: a 1.1 envelope gets a VersionMismatch a 1.1
     * client can read.
     */
    @Test
    void soap11EnvelopeOnTheSoap12PathIsAVersionMismatchIn11() throws Exception {
        send(MockBankEndpoint.PATH_12, SOAP11_ENV,
                "<b:GetBalanceRequest><b:accountId>ACC-1001</b:accountId></b:GetBalanceRequest>")
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("text/xml"))
                .andExpect(xpath("//faultcode").string("soap:VersionMismatch"))
                .andExpect(xpath("//faultstring").string("Expected a SOAP 1.2 Envelope"));
    }

    @Test
    void soap12EnvelopeOnTheSoap11PathIsAVersionMismatch() throws Exception {
        send(MockBankEndpoint.PATH, SOAP12_ENV,
                "<b:GetBalanceRequest><b:accountId>ACC-1001</b:accountId></b:GetBalanceRequest>")
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("text/xml"))
                .andExpect(xpath("//faultcode").string("soap:VersionMismatch"))
                .andExpect(xpath("//faultstring").string("Expected a SOAP 1.1 Envelope"));
    }

    @Test
    void bothVersionsShareOneLedger() throws Exception {
        soap12("""
                <b:TransferRequest><b:fromAccountId>ACC-1001</b:fromAccountId><b:toAccountId>ACC-2001</b:toAccountId>
                <b:amount>100.50</b:amount></b:TransferRequest>""")
                .andExpect(status().isOk())
                .andExpect(xpath("//*[local-name()='newBalance']").string("2399.50"));
        send(MockBankEndpoint.PATH, SOAP11_ENV,
                "<b:GetBalanceRequest><b:accountId>ACC-2001</b:accountId></b:GetBalanceRequest>")
                .andExpect(xpath("//*[local-name()='balance']").string("100.50"));
    }

    @Test
    void soap12WsdlUsesTheSoap12BindingAndItsOwnAddress() throws Exception {
        mvc.perform(get(MockBankEndpoint.PATH_12).param("wsdl", ""))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap12/\"")))
                .andExpect(content().string(not(containsString("http://schemas.xmlsoap.org/wsdl/soap/\""))))
                .andExpect(content().string(containsString("location=\"http://localhost/mock-bank/ws12\"")))
                .andExpect(content().string(containsString("soapAction=\"urn:example:bank:v1:Transfer\"")));
    }

    @Test
    void soap11WsdlIsUnchanged() throws Exception {
        mvc.perform(get(MockBankEndpoint.PATH).param("wsdl", ""))
                .andExpect(content().string(containsString("xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\"")))
                .andExpect(content().string(containsString("location=\"http://localhost/mock-bank/ws\"")));
    }

    /**
     * If bank.wsdl is ever edited so the derivation no longer applies, startup
     * fails instead of serving 1.1 as 1.2.
     */
    @Test
    void derivationFailsLoudlyWhenBankWsdlChanges() {
        assertThatThrownBy(() -> MockBankEndpoint.soap12("<wsdl:definitions/>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bank.wsdl no longer contains");
        assertThat(MockBankEndpoint.soap12(
                "<!-- Legacy Bank Service, SOAP 1.1 document/literal. --><d xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\"/>"))
                .contains("soap12").contains("SOAP 1.2");
    }
}
