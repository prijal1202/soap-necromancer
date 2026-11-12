package dev.soapnecromancer.mockbank;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory state of the legacy bank. Thread-safe via a single lock; this is a demo backend. */
@Component
public class BankLedger {

    public record Account(String id, String currency, boolean overdraftAllowed, BigDecimal balance) {}

    public record Line(String transactionId, LocalDate date, String type, BigDecimal amount, String description) {}

    public record TransferResult(String transactionId, BigDecimal newBalance, OffsetDateTime executedAt) {}

    private final Clock clock;
    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, Deque<Line>> lines = new HashMap<>();
    private final AtomicLong txSeq = new AtomicLong();

    public BankLedger() {
        this(Clock.systemUTC());
    }

    BankLedger(Clock clock) {
        this.clock = clock;
        reset();
    }

    /** Restores the seeded demo accounts. Used by tests and by the dev app. */
    public synchronized void reset() {
        accounts.clear();
        lines.clear();
        txSeq.set(1000);
        open("ACC-1001", "USD", false, new BigDecimal("2500.00"));
        open("ACC-1002", "USD", true, new BigDecimal("150.00"));
        open("ACC-2001", "USD", false, new BigDecimal("0.00"));
    }

    private void open(String id, String currency, boolean overdraft, BigDecimal opening) {
        accounts.put(id, new Account(id, currency, overdraft, opening));
        Deque<Line> deque = new ArrayDeque<>();
        if (opening.signum() > 0) {
            deque.addFirst(new Line(nextTxId(), LocalDate.now(clock), "CREDIT", opening, "Opening balance"));
        }
        lines.put(id, deque);
    }

    public synchronized Account account(String id) {
        Account a = accounts.get(id);
        if (a == null) throw new MockBankFault("soap:Client", "Unknown account: " + id);
        return a;
    }

    public synchronized TransferResult transfer(String from, String to, BigDecimal amount, String memo) {
        if (amount == null || amount.signum() <= 0) {
            throw new MockBankFault("soap:Client", "Amount must be positive");
        }
        if (from.equals(to)) throw new MockBankFault("soap:Client", "Cannot transfer to the same account");
        Account src = account(from);
        Account dst = account(to);
        BigDecimal newSrc = src.balance().subtract(amount);
        if (newSrc.signum() < 0 && !src.overdraftAllowed()) {
            throw new MockBankFault("soap:Client", "Insufficient funds in " + from);
        }
        accounts.put(from, new Account(src.id(), src.currency(), src.overdraftAllowed(), newSrc));
        accounts.put(to, new Account(dst.id(), dst.currency(), dst.overdraftAllowed(), dst.balance().add(amount)));

        String txId = nextTxId();
        LocalDate today = LocalDate.now(clock);
        String desc = memo == null || memo.isBlank() ? null : memo;
        lines.get(from).addFirst(new Line(txId, today, "DEBIT", amount, desc));
        lines.get(to).addFirst(new Line(txId, today, "CREDIT", amount, desc));
        return new TransferResult(txId, newSrc, OffsetDateTime.now(clock));
    }

    /** Newest first, optionally capped. */
    public synchronized List<Line> statement(String id, Integer maxLines) {
        account(id);
        List<Line> all = new ArrayList<>(lines.get(id));
        if (maxLines != null && maxLines >= 0 && maxLines < all.size()) return all.subList(0, maxLines);
        return all;
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String nextTxId() {
        return "TX-" + txSeq.incrementAndGet();
    }
}
