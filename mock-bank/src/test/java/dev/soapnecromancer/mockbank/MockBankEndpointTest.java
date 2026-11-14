package dev.soapnecromancer.mockbank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MockBankEndpointTest {

        @Autowired
        MockMvc mvc;
        @Autowired
        BankLedger ledger;

        @BeforeEach
        void reset() {
                ledger.reset();
        }

        private ResultActions soap(String payload) throws Exception {
                String env = """
                                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:b="urn:example:bank:v1">
                                  <soap:Body>%s</soap:Body>
                                </soap:Envelope>"""
                                .formatted(payload);
                return mvc.perform(post(MockBankEndpoint.PATH).contentType("text/xml").content(env));
        }

        @Test
        void getBalanceReturnsSeededAccount() throws Exception {
                soap("<b:GetBalanceRequest><b:accountId>ACC-1001</b:accountId></b:GetBalanceRequest>")
                                .andExpect(status().isOk())
                                .andExpect(xpath("//*[local-name()='GetBalanceResponse']/*[local-name()='balance']")
                                                .string("2500.00"))
                                .andExpect(xpath("//*[local-name()='overdraftAllowed']").string("false"))
                                .andExpect(xpath("//*[local-name()='currency']").string("USD"));
        }

        @Test
        void transferMovesFundsAndAppearsInStatement() throws Exception {
                soap("""
                                <b:TransferRequest><b:fromAccountId>ACC-1001</b:fromAccountId><b:toAccountId>ACC-2001</b:toAccountId>
                                <b:amount>100.50</b:amount><b:memo>Rent</b:memo></b:TransferRequest>""")
                                .andExpect(status().isOk())
                                .andExpect(xpath("//*[local-name()='newBalance']").string("2399.50"))
                                .andExpect(xpath("//*[local-name()='transactionId']").exists());

                soap("<b:GetStatementRequest><b:accountId>ACC-2001</b:accountId></b:GetStatementRequest>")
                                .andExpect(status().isOk())
                                .andExpect(xpath("count(//*[local-name()='line'])").number(1.0))
                                .andExpect(xpath("//*[local-name()='line']/*[local-name()='type']").string("CREDIT"))
                                .andExpect(xpath("//*[local-name()='line']/*[local-name()='description']")
                                                .string("Rent"));
        }

        @Test
        void statementOmitsOptionalDescriptionAndHonoursMaxLines() throws Exception {
                soap("""
                                <b:TransferRequest><b:fromAccountId>ACC-1001</b:fromAccountId><b:toAccountId>ACC-1002</b:toAccountId>
                                <b:amount>1</b:amount></b:TransferRequest>""")
                                .andExpect(status().isOk());

                soap("<b:GetStatementRequest><b:accountId>ACC-1001</b:accountId><b:maxLines>1</b:maxLines></b:GetStatementRequest>")
                                .andExpect(xpath("count(//*[local-name()='line'])").number(1.0))
                                .andExpect(xpath("//*[local-name()='line']/*[local-name()='type']").string("DEBIT"))
                                .andExpect(xpath("//*[local-name()='line']/*[local-name()='description']")
                                                .doesNotExist());
        }

        @Test
        void emptyStatementHasNoLines() throws Exception {
                soap("<b:GetStatementRequest><b:accountId>ACC-2001</b:accountId></b:GetStatementRequest>")
                                .andExpect(status().isOk())
                                .andExpect(xpath("count(//*[local-name()='line'])").number(0.0));
        }

        @Test
        void insufficientFundsIsASoapFault() throws Exception {
                soap("""
                                <b:TransferRequest><b:fromAccountId>ACC-2001</b:fromAccountId><b:toAccountId>ACC-1001</b:toAccountId>
                                <b:amount>5</b:amount></b:TransferRequest>""")
                                .andExpect(status().isInternalServerError())
                                .andExpect(xpath("//faultcode").string("soap:Client"))
                                .andExpect(xpath("//faultstring").string(containsString("Insufficient funds")));
        }

        @Test
        void unknownAccountIsASoapFault() throws Exception {
                soap("<b:GetBalanceRequest><b:accountId>NOPE</b:accountId></b:GetBalanceRequest>")
                                .andExpect(status().isInternalServerError())
                                .andExpect(xpath("//faultstring").string("Unknown account: NOPE"));
        }

        /**
         * 1E+300000000 used to make BigDecimal build a billion-digit number while
         * holding the ledger lock.
         */
        @Test
        void exponentAmountIsAClientFaultAndCostsNothing() throws Exception {
                long start = System.nanoTime();
                soap("""
                                <b:TransferRequest><b:fromAccountId>ACC-1002</b:fromAccountId><b:toAccountId>ACC-2001</b:toAccountId>
                                <b:amount>1E+300000000</b:amount></b:TransferRequest>""")
                                .andExpect(status().isInternalServerError())
                                .andExpect(xpath("//faultcode").string("soap:Client"))
                                .andExpect(xpath("//faultstring").string("amount is not a valid xs:decimal"));
                assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
                assertThat(ledger.account("ACC-2001").balance()).isEqualByComparingTo("0");
        }

        @Test
        void amountWithTooManyDigitsIsAClientFault() throws Exception {
                soap("""
                                <b:TransferRequest><b:fromAccountId>ACC-1002</b:fromAccountId><b:toAccountId>ACC-2001</b:toAccountId>
                                <b:amount>1.%s</b:amount></b:TransferRequest>"""
                                .formatted("1".repeat(19)))
                                .andExpect(status().isInternalServerError())
                                .andExpect(xpath("//faultcode").string("soap:Client"))
                                .andExpect(xpath("//faultstring").string(containsString("at most 18 digits")));
        }

        @Test
        void overlongMemoIsAClientFault() throws Exception {
                soap("""
                                <b:TransferRequest><b:fromAccountId>ACC-1001</b:fromAccountId><b:toAccountId>ACC-2001</b:toAccountId>
                                <b:amount>1</b:amount><b:memo>%s</b:memo></b:TransferRequest>"""
                                .formatted("m".repeat(141)))
                                .andExpect(status().isInternalServerError())
                                .andExpect(xpath("//faultstring").string(containsString("memo is longer than 140")));
                assertThat(ledger.account("ACC-2001").balance()).isEqualByComparingTo("0");
        }

        @Test
        void oversizedEnvelopeIsAClientFault() throws Exception {
                String big = "<b:GetBalanceRequest><b:accountId>ACC-1001</b:accountId></b:GetBalanceRequest>"
                                + "<!--" + "x".repeat(MockBankEndpoint.MAX_ENVELOPE_BYTES) + "-->";
                soap(big)
                                .andExpect(status().isInternalServerError())
                                .andExpect(xpath("//faultcode").string("soap:Client"))
                                .andExpect(xpath("//faultstring").string(containsString("larger than")));
        }

        @Test
        void emptyBodyIsASoapFaultNotAJsonError() throws Exception {
                mvc.perform(post(MockBankEndpoint.PATH).contentType("text/xml"))
                                .andExpect(status().isInternalServerError())
                                .andExpect(content().contentTypeCompatibleWith("text/xml"))
                                .andExpect(xpath("//faultcode").string("soap:Client"));
        }

        @Test
        void doctypeIsRejected() throws Exception {
                String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>&e;</soap:Body></soap:Envelope>";
                mvc.perform(post(MockBankEndpoint.PATH).contentType("text/xml").content(evil))
                                .andExpect(status().isInternalServerError())
                                .andExpect(xpath("//faultstring").string("Malformed SOAP envelope"));
        }

        @Test
        void forwardedHostCannotBreakTheWsdl() throws Exception {
                // The address comes from request headers when no public base URL is configured.
                mvc.perform(get(MockBankEndpoint.PATH).param("wsdl", "").header("Host", "evil\"><x/>"))
                                .andExpect(status().isOk())
                                .andExpect(content().string(not(containsString("\"><x/>"))));
        }

        @Test
        void wsdlAddressPointsAtServingHost() throws Exception {
                mvc.perform(get(MockBankEndpoint.PATH).param("wsdl", ""))
                                .andExpect(status().isOk())
                                .andExpect(content()
                                                .string(containsString("location=\"http://localhost/mock-bank/ws\"")))
                                .andExpect(content().string(not(containsString("localhost:8080"))));
        }
}
