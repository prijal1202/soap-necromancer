package dev.soapnecromancer.mockbank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bounds that keep the shared demo ledger small, whatever visitors send. */
class BankLedgerTest {
    private final BankLedger ledger = new BankLedger(Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void statementKeepsOnlyTheNewestLinesPerAccount() {
        for (int i = 0; i < BankLedger.MAX_LINES_PER_ACCOUNT + 50; i++) {
            ledger.transfer("ACC-1002", "ACC-2001", new BigDecimal("0.01"), "line " + i);
        }
        var lines = ledger.statement("ACC-2001", null);
        assertThat(lines).hasSize(BankLedger.MAX_LINES_PER_ACCOUNT);
        assertThat(lines.get(0).description()).isEqualTo("line " + (BankLedger.MAX_LINES_PER_ACCOUNT + 49));
        // The balance still counts every transfer; only the history is trimmed.
        assertThat(ledger.account("ACC-2001").balance()).isEqualByComparingTo("2.50");
    }

    @Test
    void hugeScaleAmountIsRejectedBeforeAnyArithmetic() {
        assertThatThrownBy(() -> ledger.transfer("ACC-1002", "ACC-2001", new BigDecimal("1E-300000000"), null))
                .isInstanceOfSatisfying(MockBankFault.class,
                        f -> assertThat(f.code()).isEqualTo(MockBankFault.Code.SENDER));
        assertThatThrownBy(() -> ledger.transfer("ACC-1002", "ACC-2001", new BigDecimal("1E+300000000"), null))
                .isInstanceOf(MockBankFault.class);
    }

    @Test
    void negativeOrNullMaxLinesMeansNoCap() {
        ledger.transfer("ACC-1001", "ACC-2001", BigDecimal.ONE, null);
        ledger.transfer("ACC-1001", "ACC-2001", BigDecimal.ONE, null);
        assertThat(ledger.statement("ACC-2001", -1)).hasSize(2);
        assertThat(ledger.statement("ACC-2001", null)).hasSize(2);
        assertThat(ledger.statement("ACC-2001", 1)).hasSize(1);
        assertThat(ledger.statement("ACC-2001", 0)).isEmpty();
    }

    @Test
    void resetAndSnapshotReturnsTheSeed() {
        ledger.transfer("ACC-1001", "ACC-2001", new BigDecimal("10"), null);
        assertThat(ledger.resetAndSnapshot()).containsOnlyKeys("ACC-1001", "ACC-1002", "ACC-2001")
                .hasEntrySatisfying("ACC-2001", b -> assertThat(b).isEqualByComparingTo("0"));
    }
}
