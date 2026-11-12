package dev.soapnecromancer.mockbank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Shared demo state: a stray transfer is undone by POST /mock-bank/reset and by the scheduled reset. */
@SpringBootTest(properties = {"mockbank.reset-interval=PT0.3S", "mockbank.public-base-url=https://necro.example.com/"})
@AutoConfigureMockMvc
class MockBankResetTest {
    @Autowired MockMvc mvc;
    @Autowired BankLedger ledger;

    @Test
    void resetEndpointRestoresSeededBalances() throws Exception {
        ledger.transfer("ACC-1001", "ACC-2001", new BigDecimal("999.00"), "stray");
        mvc.perform(post("/mock-bank/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reset"))
                .andExpect(jsonPath("$.balances['ACC-1001']").value(2500.00))
                .andExpect(jsonPath("$.balances['ACC-2001']").value(0.00));
        assertThat(ledger.statement("ACC-2001", null)).isEmpty();
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
                .andExpect(content().string(containsString("location=\"https://necro.example.com/mock-bank/ws\"")));
    }
}
