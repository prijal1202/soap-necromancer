package dev.soapnecromancer.mockbank;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory state of the legacy bank, shared by every visitor of the hosted
 * demo. Thread-safe via a
 * single lock. Inputs are bounded so that no request can make the shared state,
 * or the work done
 * under the lock, grow without limit.
 */
@Component
public class BankLedger {

    /**
     * Amounts keep every digit (the demo proves decimal exactness), within this
     * many on each side.
     */
    public static final int MAX_AMOUNT_DIGITS = 18;
    public static final int MAX_MEMO_LENGTH = 140;
    /** Statement lines kept per account; older ones are dropped. */
    public static final int MAX_LINES_PER_ACCOUNT = 200;

    public record Account(String id, String currency, boolean overdraftAllowed, BigDecimal balance) {
    }

    public record Line(String transactionId, LocalDate date, String type, BigDecimal amount, String description) {
    }

    public record TransferResult(String transactionId, BigDecimal newBalance, OffsetDateTime executedAt) {
    }

    private static final List<String> SEEDED = List.of("ACC-1001", "ACC-1002", "ACC-2001");

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
        open(SEEDED.get(0), "USD", false, new BigDecimal("2500.00"));
        open(SEEDED.get(1), "USD", true, new BigDecimal("150.00"));
        open(SEEDED.get(2), "USD", false, new BigDecimal("0.00"));
    }

    /**
     * Resets and returns the seeded balances in one step, so no transfer can slip
     * in between.
     */
    public synchronized Map<String, BigDecimal> resetAndSnapshot() {
        reset();
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (String id : SEEDED)
            balances.put(id, accounts.get(id).balance());
        return balances;
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
        if (a == null)
            throw MockBankFault.sender("Unknown account: " + shorten(id));
        return a;
    }

    public synchronized TransferResult transfer(String from, String to, BigDecimal amount, String memo) {
        if (amount == null || amount.signum() <= 0) {
            throw MockBankFault.sender("Amount must be positive");
        }
        checkRange(amount);
        if (memo != null && memo.length() > MAX_MEMO_LENGTH) {
            throw MockBankFault.sender("memo is longer than " + MAX_MEMO_LENGTH + " characters");
        }
        if (from.equals(to))
            throw MockBankFault.sender("Cannot transfer to the same account");
        Account src = account(from);
        Account dst = account(to);
        BigDecimal newSrc = src.balance().subtract(amount);
        if (newSrc.signum() < 0 && !src.overdraftAllowed()) {
            throw MockBankFault.sender("Insufficient funds in " + src.id());
        }
        accounts.put(from, new Account(src.id(), src.currency(), src.overdraftAllowed(), newSrc));
        accounts.put(to, new Account(dst.id(), dst.currency(), dst.overdraftAllowed(), dst.balance().add(amount)));

        String txId = nextTxId();
        LocalDate today = LocalDate.now(clock);
        String desc = memo == null || memo.isBlank() ? null : memo;
        record(from, new Line(txId, today, "DEBIT", amount, desc));
        record(to, new Line(txId, today, "CREDIT", amount, desc));
        return new TransferResult(txId, newSrc, OffsetDateTime.now(clock));
    }

    /**
     * BigDecimal arithmetic costs grow with the scale difference, so
     * {@code 1E+300000000} would
     * build a billion-digit number while holding the lock. The endpoint already
     * rejects exponent
     * notation; this guards every other caller.
     */
    private static void checkRange(BigDecimal amount) {
        int integerDigits = amount.precision() - amount.scale();
        if (amount.scale() > MAX_AMOUNT_DIGITS || integerDigits > MAX_AMOUNT_DIGITS) {
            throw MockBankFault.sender("Amount must have at most " + MAX_AMOUNT_DIGITS
                    + " digits before and after the decimal point");
        }
    }

    private void record(String account, Line line) {
        Deque<Line> deque = lines.get(account);
        deque.addFirst(line);
        while (deque.size() > MAX_LINES_PER_ACCOUNT)
            deque.removeLast();
    }

    /**
     * Newest first, optionally capped. A null or negative {@code maxLines} means no
     * cap.
     */
    public synchronized List<Line> statement(String id, Integer maxLines) {
        account(id);
        List<Line> all = List.copyOf(lines.get(id));
        if (maxLines == null || maxLines < 0 || maxLines >= all.size())
            return all;
        return all.subList(0, maxLines);
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String nextTxId() {
        return "TX-" + txSeq.incrementAndGet();
    }

    /** Request text echoed in faults stays short. */
    static String shorten(String s) {
        return s.length() <= 64 ? s : s.substring(0, 64) + "...";
    }
}
