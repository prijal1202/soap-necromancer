package dev.soapnecromancer.mockbank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every visitor of the hosted demo shares one in-memory bank. So that a stray transfer cannot change
 * the next person's balances, the ledger goes back to its seeded accounts on demand
 * ({@code POST /mock-bank/reset}) and on a schedule ({@code mockbank.reset-interval}, default 30 min).
 * Resetting only restores the seed, so the endpoint is safe to leave public.
 */
@RestController
@Configuration
@EnableScheduling
public class MockBankReset {
    private static final Logger log = LoggerFactory.getLogger(MockBankReset.class);
    private static final List<String> SEEDED = List.of("ACC-1001", "ACC-1002", "ACC-2001");

    private final BankLedger ledger;

    public MockBankReset(BankLedger ledger) {
        this.ledger = ledger;
    }

    /** Restores the seeded accounts and returns their balances. */
    @PostMapping("/mock-bank/reset")
    public Map<String, Object> reset() {
        ledger.reset();
        Map<String, Object> balances = new LinkedHashMap<>();
        for (String id : SEEDED) balances.put(id, ledger.account(id).balance());
        return Map.of("status", "reset", "balances", balances);
    }

    @Scheduled(initialDelayString = "${mockbank.reset-interval:PT30M}", fixedDelayString = "${mockbank.reset-interval:PT30M}")
    void scheduledReset() {
        ledger.reset();
        log.info("mock-bank ledger reset to its seeded accounts");
    }
}
