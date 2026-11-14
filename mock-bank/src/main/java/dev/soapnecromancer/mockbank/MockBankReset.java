package dev.soapnecromancer.mockbank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every visitor of the hosted demo shares one in-memory bank. So that a stray
 * transfer cannot change
 * the next person's balances, the ledger goes back to its seeded accounts on
 * demand
 * ({@code POST /mock-bank/reset}) and on a schedule
 * ({@code mockbank.reset-interval}, default 30 min).
 *
 * <p>
 * The endpoint is public, so it is limited to one reset per
 * {@code mockbank.reset-cooldown}
 * (default 30 s; 429 with {@code Retry-After} otherwise), and it only accepts
 * {@code Content-Type: application/json}. A browser cannot send that cross-site
 * without a CORS
 * preflight, which this app never grants, so another web page cannot wipe
 * someone's live demo.
 */
@RestController
public class MockBankReset {
    private static final Logger log = LoggerFactory.getLogger(MockBankReset.class);

    private final BankLedger ledger;
    private final long cooldownNanos;
    /**
     * System.nanoTime() of the last manual reset; Long.MIN_VALUE until the first
     * one.
     */
    private final AtomicLong lastManualReset = new AtomicLong(Long.MIN_VALUE);

    public MockBankReset(BankLedger ledger, @Value("${mockbank.reset-cooldown:PT30S}") Duration cooldown) {
        this.ledger = ledger;
        this.cooldownNanos = cooldown.toNanos();
    }

    /** Restores the seeded accounts and returns their balances. */
    @PostMapping(path = "/mock-bank/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> reset() {
        long now = System.nanoTime();
        long last = lastManualReset.get();
        boolean cooled = last == Long.MIN_VALUE || now - last >= cooldownNanos;
        // compareAndSet: of two concurrent requests after the cooldown, exactly one
        // resets.
        if (!cooled || !lastManualReset.compareAndSet(last, now)) {
            long waitNanos = last == Long.MIN_VALUE ? cooldownNanos : cooldownNanos - (now - last);
            long retryAfter = Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .body(Map.of("status", "too-soon", "retryAfterSeconds", retryAfter));
        }
        return ResponseEntity.ok(Map.of("status", "reset", "balances", ledger.resetAndSnapshot()));
    }

    @Scheduled(initialDelayString = "${mockbank.reset-interval:PT30M}", fixedDelayString = "${mockbank.reset-interval:PT30M}")
    void scheduledReset() {
        ledger.reset();
        log.info("mock-bank ledger reset to its seeded accounts");
    }
}
