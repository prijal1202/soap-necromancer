package dev.soapnecromancer.mockbank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Shared demo state: a stray transfer is undone by POST /mock-bank/reset and by
 * the scheduled reset.
 */
@SpringBootTest(properties = { "mockbank.reset-interval=PT0.3S", "mockbank.reset-cooldown=PT1H",
        "mockbank.public-base-url=https://necro.example.com/" })
@AutoConfigureMockMvc
class MockBankResetTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    BankLedger ledger;

    /**
     * One test, because the cooldown makes the second manual reset in this context
     * a 429.
     */
    @Test
    void resetRestoresSeededBalancesThenCoolsDown() throws Exception {
        ledger.transfer("ACC-1001", "ACC-2001", new BigDecimal("999.00"), "stray");
        mvc.perform(post("/mock-bank/reset").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reset"))
                .andExpect(jsonPath("$.balances['ACC-1001']").value(2500.00))
                .andExpect(jsonPath("$.balances['ACC-2001']").value(0.00));
        assertThat(ledger.statement("ACC-2001", null)).isEmpty();

        mvc.perform(post("/mock-bank/reset").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value("too-soon"));
    }

    /**
     * A cross-site HTML form can only send form or text/plain bodies; those never
     * reach the reset.
     */
    @Test
    void resetRefusesFormPostsFromOtherSites() throws Exception {
        mvc.perform(post("/mock-bank/reset").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/mock-bank/reset").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("x=1"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void scheduledResetUndoesAStrayTransfer() throws Exception {
        ledger.transfer("ACC-1001", "ACC-2001", new BigDecimal("1.00"), "stray");
        long deadline = System.currentTimeMillis() + 5_000;
        while (ledger.account("ACC-2001").balance().signum() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(ledger.account("ACC-2001").balance()).isEqualByComparingTo("0");
    }

    @Test
    void configuredPublicBaseUrlWinsOverTheRequestHost() throws Exception {
        mvc.perform(get(MockBankEndpoint.PATH).param("wsdl", ""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("location=\"https://necro.example.com/mock-bank/ws\"")));
    }
}
