package com.api.inventory.service;

import com.api.inventory.entity.BankPayment;
import com.api.inventory.entity.PaymentEvent;
import com.api.inventory.entity.PaymentIntent;
import com.api.inventory.repository.BankPaymentRepository;
import com.api.inventory.repository.PaymentEventRepository;
import com.api.inventory.repository.PaymentIntentRepository;
import com.api.inventory.security.CurrentUser;
import com.api.inventory.service.payments.BankAccountGateway;
import com.api.inventory.service.payments.BankGatewayClient;
import com.api.inventory.service.payments.BankGatewayClient.Answer;
import com.api.inventory.service.payments.PaymentGateway;
import com.api.inventory.service.payments.TestBankGatewayClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Paying from a bank account, step by step (the page /pay/bank):
 *
 *   1. requestCode  the customer picks a bank and enters the account number; the gateway asks that bank to send a
 *                   one-time code to the phone registered with the account (valid 5 minutes, at most 3 codes)
 *   2. pay          the customer enters the code; the bank debits the account and the money reaches our account,
 *                   with the bank's journal number. The order is then confirmed exactly like any paid order
 *                   (OnlinePaymentService.complete): stock is taken, sellers are told to pack, the customer is told.
 *
 * Wrong code 3 times, a 4th code, or a refusal by the bank ends the attempt (the customer can start a new one).
 * When the bank was asked to take the money but its answer never came, nobody knows whether money moved: the
 * payment waits as CHECK_BANK, staff are told, and they settle it after asking the bank (settle).
 * Only the bank, the last 4 digits of the account, the gateway's transaction number and the bank's journal number are
 * kept; the full account number and the code are never stored or written to the log. Every step is recorded in
 * payment_events.
 */
@Service
public class BankPaymentService {

    private static final Duration CODE_VALID = Duration.ofMinutes(5);
    private static final int MAX_CODES = 3;
    private static final int MAX_WRONG = 3;
    /**
     * Code requests one customer may make in an hour, over all their payments, sent or refused: nobody floods a
     * stranger's phone, and nobody tries account numbers one after another to see which exist.
     */
    private static final int MAX_REQUESTS_PER_HOUR = 10;

    public record BankView(String reference, Long orderId, BigDecimal amount, String status, String bankCode, String bankName,
                           String accountLast4, Instant codeExpiresAt, int wrongCodesLeft, int codesLeft, boolean testMode,
                           String testCode, String bankReference, String message) {
    }

    /** For staff: a payment whose result must be checked with the bank. */
    public record ToCheck(String reference, Long orderId, BigDecimal amount, String customerEmail, String bankName,
                          String accountLast4, String gatewayTransactionId, Instant askedAt) {
    }

    /** What a step decided: an answer for the page, and/or a result for OnlinePaymentService once committed. */
    private record Outcome(BankView view, PaymentGateway.Result result) {
    }

    /** A previously committed reservation, or a result that needs no external bank call. */
    private record DebitPreparation(Outcome immediate, String gatewayTransactionId) {
    }

    private final PaymentIntentRepository intents;
    private final BankPaymentRepository bankPayments;
    private final PaymentEventRepository events;
    private final ObjectProvider<BankGatewayClient> clients;
    private final OnlinePaymentService onlinePayments;
    private final NotificationService notify;
    private final TransactionTemplate tx;

    public BankPaymentService(PaymentIntentRepository intents, BankPaymentRepository bankPayments, PaymentEventRepository events,
                              ObjectProvider<BankGatewayClient> clients, OnlinePaymentService onlinePayments,
                              NotificationService notify, PlatformTransactionManager transactions) {
        this.intents = intents;
        this.bankPayments = bankPayments;
        this.events = events;
        this.clients = clients;
        this.onlinePayments = onlinePayments;
        this.notify = notify;
        this.tx = new TransactionTemplate(transactions);
    }

    public List<BankGatewayClient.Bank> banks() {
        return client().banks();
    }

    /** What the page shows. Only the customer who owns the payment (or staff checking payments). */
    public BankView view(String reference) {
        onlinePayments.status(reference); // checks who is asking
        PaymentIntent intent = intent(reference);
        BankPayment bp = bankPayments.findByIntentReference(intent.getReference()).orElse(null);
        return view(intent, bp, null);
    }

    /** Step 1: the bank sends the one-time code to the phone registered with this account. */
    public BankView requestCode(String reference, String bankCode, String accountNumber) {
        onlinePayments.status(reference); // checks who is asking
        BankGatewayClient client = client();
        String account = accountNumber == null ? "" : accountNumber.replaceAll("[\\s-]", "");
        if (!account.matches("\\d{6,20}")) {
            throw new IllegalArgumentException("Enter the account number: digits only, as on your bank book or app.");
        }
        BankGatewayClient.Bank bank = client.banks().stream().filter(b -> b.code().equals(bankCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose your bank."));
        String me = CurrentUser.email();
        if (me != null && events.countByActorAndStepAndAtAfter(me, "CODE_REQUESTED", Instant.now().minus(Duration.ofHours(1))) >= MAX_REQUESTS_PER_HOUR) {
            throw new IllegalStateException("Too many codes were asked for in the last hour. Please wait a while and try again.");
        }

        Outcome outcome = tx.execute(status -> {
            PaymentIntent intent = openIntent(reference);
            BankPayment bp = bankPayments.findByIntentReferenceForUpdate(intent.getReference())
                    .orElseGet(() -> newBankPayment(intent, client));
            if (BankPayment.CHECK_BANK.equals(bp.getStatus())) {
                throw new IllegalStateException("We are checking this payment with your bank. Please do not pay again.");
            }
            if (!BankPayment.STARTED.equals(bp.getStatus()) && !BankPayment.CODE_SENT.equals(bp.getStatus())) {
                throw new IllegalStateException("This payment is already finished. Start a new one from your order.");
            }
            if (bp.getCodesSent() >= MAX_CODES) {
                record(intent.getReference(), "TOO_MANY_CODES", "REFUSED", null);
                return new Outcome(null, fail(bp, "Three codes were already sent, so this payment was stopped. Nothing was taken."));
            }
            if (bp.getGatewayTransactionId() == null) {
                bp.setGatewayTransactionId(client.start(intent.getReference(), intent.getAmount(),
                        "DP DrukBazaars order " + intent.getOrderId(), intent.getCustomerEmail()));
                record(intent.getReference(), "STARTED", "OK", "Gateway transaction " + bp.getGatewayTransactionId());
            }
            bp.setBankCode(bank.code());
            bp.setBankName(bank.name());
            bp.setAccountLast4(account.substring(account.length() - 4));
            record(intent.getReference(), "CODE_REQUESTED", null, bank.name() + ", account ending " + bp.getAccountLast4());

            Answer answer = client.requestCode(bp.getGatewayTransactionId(), bank.code(), account);
            if (!answer.ok()) {
                // an earlier code (for another account) must not be usable any more
                bp.setStatus(BankPayment.STARTED);
                bp.setCodeExpiresAt(null);
                bankPayments.save(bp);
                record(intent.getReference(), "CODE_REFUSED", answer.code(), answer.message());
                return new Outcome(view(intent, bp, answer.message() == null ? "The bank could not send a code." : answer.message()), null);
            }
            Instant now = Instant.now();
            bp.setStatus(BankPayment.CODE_SENT);
            bp.setCodeSentAt(now);
            bp.setCodeExpiresAt(now.plus(CODE_VALID));
            bp.setCodesSent(bp.getCodesSent() + 1);
            bp.setWrongCodes(0);
            bankPayments.save(bp);
            record(intent.getReference(), "CODE_SENT", "OK", null);
            return new Outcome(view(intent, bp, answer.message()), null);
        });
        return finish(reference, outcome);
    }

    /**
     * Step 2: commit DEBIT_PENDING first, then call the bank OUTSIDE any DB transaction.
     * If the process crashes after the call, the durable pending state prevents another debit.
     * Staff must verify an unknown outcome with the bank; never retry a debit automatically.
     */
    public BankView pay(String reference, String code) {
        onlinePayments.status(reference); // checks who is asking
        BankGatewayClient client = client();
        String given = code == null ? "" : code.trim();
        if (!given.matches("\\d{4,8}")) {
            throw new IllegalArgumentException("Enter the code from the text message (digits only).");
        }

        DebitPreparation preparation = tx.execute(status -> {
            PaymentIntent intent = openIntent(reference); // locks intent
            BankPayment bp = bankPayments.findByIntentReferenceForUpdate(intent.getReference())
                    .orElseThrow(() -> new IllegalStateException("Ask for a code first."));
            if (BankPayment.PAID.equals(bp.getStatus())) {
                // Bank paid but process stopped before finishing order: no second debit.
                return new DebitPreparation(new Outcome(null, paidResult(intent, bp)), null);
            }
            if (BankPayment.DEBIT_PENDING.equals(bp.getStatus()) || BankPayment.CHECK_BANK.equals(bp.getStatus())) {
                throw new IllegalStateException("The bank debit is processing or awaiting verification. Do not pay again.");
            }
            if (!BankPayment.CODE_SENT.equals(bp.getStatus())) {
                throw new IllegalStateException("Ask for a code first.");
            }
            if (bp.getCodeExpiresAt() != null && bp.getCodeExpiresAt().isBefore(Instant.now())) {
                record(intent.getReference(), "CODE_EXPIRED", "CODE_EXPIRED", null);
                return new DebitPreparation(new Outcome(view(intent, bp, "The code has expired. Ask for a new code."), null), null);
            }
            if (bp.getGatewayTransactionId() == null || bp.getGatewayTransactionId().isBlank()) {
                throw new IllegalStateException("Bank transaction number is missing. Ask staff for assistance.");
            }
            bp.setStatus(BankPayment.DEBIT_PENDING);
            bp.setDebitRequestedAt(Instant.now());
            bankPayments.save(bp);
            record(intent.getReference(), "DEBIT_REQUESTED", "PENDING", "Gateway transaction " + bp.getGatewayTransactionId());
            return new DebitPreparation(null, bp.getGatewayTransactionId());
        }); // DEBIT_PENDING is committed before the network call

        if (preparation.immediate() != null) {
            return finish(reference, preparation.immediate());
        }

        Answer answer;
        try {
            answer = client.debit(preparation.gatewayTransactionId(), given);
        } catch (RuntimeException e) {
            // Unexpected client failure after sending a debit: money might have moved.
            // Don't log the exception message: it could contain confidential gateway data.
            answer = Answer.no("NO_ANSWER", "The bank's result could not be confirmed.");
        }
        if (answer == null) {
            answer = Answer.no("NO_ANSWER", "The bank's result could not be confirmed.");
        }
        final Answer bankAnswer = answer;

        Outcome outcome = tx.execute(status -> {
            PaymentIntent intent = intents.findByReferenceForUpdate(reference == null ? "" : reference.trim())
                    .orElseThrow(() -> new IllegalStateException("Payment not found."));
            BankPayment bp = bankPayments.findByIntentReferenceForUpdate(intent.getReference())
                    .orElseThrow(() -> new IllegalStateException("Payment not found."));
            if (!BankPayment.DEBIT_PENDING.equals(bp.getStatus())) {
                // Staff may have reconciled an old request; never overwrite a final result.
                if (BankPayment.PAID.equals(bp.getStatus()) && !intent.isFinal()) {
                    return new Outcome(null, paidResult(intent, bp));
                }
                return new Outcome(view(intent, bp, null), null);
            }
            if (bankAnswer.ok()) {
                bp.setStatus(BankPayment.PAID);
                bp.setBankReference(bankAnswer.reference());
                bp.setCompletedAt(Instant.now());
                bankPayments.save(bp);
                record(intent.getReference(), "PAID", "OK", bp.getBankName() + ", account ending " + bp.getAccountLast4()
                        + (bankAnswer.reference() == null ? "" : ", journal " + bankAnswer.reference()));
                return new Outcome(null, paidResult(intent, bp));
            }
            if ("NO_ANSWER".equals(bankAnswer.code())) {
                bp.setStatus(BankPayment.CHECK_BANK);
                bankPayments.save(bp);
                record(intent.getReference(), "NO_ANSWER", bankAnswer.code(), bankAnswer.message());
                notify.withPermission("payments.verify", new NotificationService.Note("BANK_PAYMENT_TO_CHECK",
                        "Check a bank payment for order #" + intent.getOrderId(),
                        "The bank's debit result is unknown for Nu. " + intent.getAmount() + " from " + bp.getBankName()
                                + " account ending " + bp.getAccountLast4() + " (gateway transaction " + bp.getGatewayTransactionId()
                                + "). Verify with the bank before allowing another payment.",
                        "/order-verification"), true);
                return new Outcome(view(intent, bp, null), null);
            }
            if ("WRONG_CODE".equals(bankAnswer.code())) {
                bp.setWrongCodes(bp.getWrongCodes() + 1);
                record(intent.getReference(), "WRONG_CODE", bankAnswer.code(), null);
                if (bp.getWrongCodes() < MAX_WRONG) {
                    bp.setStatus(BankPayment.CODE_SENT);
                    bankPayments.save(bp);
                    int left = MAX_WRONG - bp.getWrongCodes();
                    return new Outcome(view(intent, bp, "That code is not right. " + left
                            + (left == 1 ? " try" : " tries") + " left."), null);
                }
                return new Outcome(null, fail(bp, "The code was wrong 3 times, so the payment was stopped. Nothing was taken."));
            }
            if ("CODE_EXPIRED".equals(bankAnswer.code())) {
                bp.setStatus(BankPayment.CODE_SENT);
                bankPayments.save(bp);
                record(intent.getReference(), "CODE_EXPIRED", bankAnswer.code(), null);
                return new Outcome(view(intent, bp, "The code has expired. Ask for a new code."), null);
            }
            if ("INSUFFICIENT_FUNDS".equals(bankAnswer.code()) || "REFUSED".equals(bankAnswer.code())) {
                record(intent.getReference(), "REFUSED", bankAnswer.code(), bankAnswer.message());
                return new Outcome(null, fail(bp, bankAnswer.message() == null ? "The bank refused the payment." : bankAnswer.message()));
            }
            // Unknown / unrecognized debit responses are never evidence that no money moved.
            bp.setStatus(BankPayment.CHECK_BANK);
            bankPayments.save(bp);
            record(intent.getReference(), "UNKNOWN_DEBIT_RESULT", bankAnswer.code(), null);
            notify.withPermission("payments.verify", new NotificationService.Note("BANK_PAYMENT_TO_CHECK",
                    "Check a bank payment for order #" + intent.getOrderId(),
                    "The bank returned an unrecognized debit result for gateway transaction "
                            + bp.getGatewayTransactionId() + ". Confirm the result with the bank before another payment.",
                    "/order-verification"), true);
            return new Outcome(view(intent, bp, null), null);
        });
        return finish(reference, outcome);
    }

    // ================= Staff: payments to check with the bank =================

    public List<ToCheck> toCheck() {
        requireVerifier();
        return bankPayments.findByStatusInOrderByCreatedAtAsc(
                List.of(BankPayment.DEBIT_PENDING, BankPayment.CHECK_BANK)).stream().map(bp -> {
            PaymentIntent intent = intents.findByReference(bp.getIntentReference()).orElse(null);
            return new ToCheck(bp.getIntentReference(), bp.getOrderId(), bp.getAmount(), intent == null ? null : intent.getCustomerEmail(),
                    bp.getBankName(), bp.getAccountLast4(), bp.getGatewayTransactionId(), bp.getDebitRequestedAt());
        }).toList();
    }

    /**
     * Staff asked the bank. Paid: the bank's journal number is recorded and the order is confirmed like any paid order.
     * Not paid: the attempt ends and the customer can pay again.
     */
    public BankView settle(String reference, boolean paid, String bankJournal) {
        requireVerifier();
        String journal = JournalNumbers.tidy(bankJournal);
        if (paid && journal == null) {
            throw new IllegalArgumentException("Enter the journal number the bank gave for this payment.");
        }
        Outcome outcome = tx.execute(status -> {
            PaymentIntent intent = intents.findByReferenceForUpdate(reference == null ? "" : reference.trim())
                    .orElseThrow(() -> new IllegalStateException("Payment not found."));
            BankPayment bp = bankPayments.findByIntentReferenceForUpdate(intent.getReference())
                    .orElseThrow(() -> new IllegalStateException("Payment not found."));
            if (!BankPayment.CHECK_BANK.equals(bp.getStatus()) && !BankPayment.DEBIT_PENDING.equals(bp.getStatus())) {
                throw new IllegalStateException("This payment does not need checking any more.");
            }
            // Allow an in-flight bank call time to finish before staff can settle it.
            if (BankPayment.DEBIT_PENDING.equals(bp.getStatus()) && bp.getDebitRequestedAt() != null
                    && bp.getDebitRequestedAt().isAfter(Instant.now().minus(Duration.ofMinutes(5)))) {
                throw new IllegalStateException("The debit was requested recently. Please wait before reconciling it with the bank.");
            }
            if (paid) {
                bp.setStatus(BankPayment.PAID);
                bp.setBankReference(journal);
                bp.setCompletedAt(Instant.now());
                bankPayments.save(bp);
                record(intent.getReference(), "SETTLED_PAID", "OK", "Checked with the bank, journal " + journal);
                return new Outcome(null, paidResult(intent, bp));
            }
            record(intent.getReference(), "SETTLED_NOT_PAID", "REFUSED", "Checked with the bank: no money was taken");
            return new Outcome(null, fail(bp, "The bank confirmed that no money was taken. You can pay again."));
        });
        return finish(reference, outcome);
    }

    // ================= Helpers =================

    /** After the step is saved: a final result goes to OnlinePaymentService (its own transactions), then the page is refreshed. */
    private BankView finish(String reference, Outcome outcome) {
        if (outcome.result() != null) {
            onlinePayments.complete(outcome.result()); // records the money and confirms the order, or closes the attempt
        }
        return outcome.view() != null ? outcome.view() : view(reference);
    }

    /** Only a bank journal is sufficient to auto-confirm a debit. If absent,
     * the gateway transaction ID remains available in BankPayment for staff. */
    private static PaymentGateway.Result paidResult(PaymentIntent intent, BankPayment bp) {
        return new PaymentGateway.Result(intent.getReference(), true, bp.getBankReference(), bp.getAmount(),
                "Bank account ending " + bp.getAccountLast4());
    }

    private PaymentGateway.Result fail(BankPayment bp, String reason) {
        bp.setStatus(BankPayment.FAILED);
        bp.setFailureReason(cut(reason));
        bp.setCompletedAt(Instant.now());
        bankPayments.save(bp);
        return new PaymentGateway.Result(bp.getIntentReference(), false, bp.getGatewayTransactionId(), null, reason);
    }

    private BankPayment newBankPayment(PaymentIntent intent, BankGatewayClient client) {
        BankPayment bp = new BankPayment();
        bp.setIntentReference(intent.getReference());
        bp.setOrderId(intent.getOrderId());
        bp.setAmount(intent.getAmount());
        bp.setTestMode(client.testMode());
        bp.setCreatedAt(Instant.now());
        return bankPayments.save(bp);
    }

    private PaymentIntent intent(String reference) {
        return intents.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new IllegalStateException("Payment not found."));
    }

    /** The attempt must be a bank-account payment that is still open. */
    private PaymentIntent openIntent(String reference) {
        PaymentIntent intent = intents.findByReferenceForUpdate(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new IllegalStateException("Payment not found."));
        if (!BankAccountGateway.CODE.equals(intent.getProvider())) {
            throw new IllegalStateException("This payment is not a bank-account payment.");
        }
        if (intent.isFinal()) {
            throw new IllegalStateException("This payment is already finished (" + intent.getStatus().toLowerCase() + ").");
        }
        return intent;
    }

    private BankView view(PaymentIntent intent, BankPayment bp, String message) {
        BankGatewayClient client = clients.getIfAvailable();
        boolean test = bp != null ? bp.isTestMode() : client != null && client.testMode();
        String status = bp == null ? BankPayment.STARTED : bp.getStatus();
        if (PaymentIntent.PAID.equals(intent.getStatus())) {
            status = BankPayment.PAID;
        } else if (intent.isFinal() && (BankPayment.STARTED.equals(status) || BankPayment.CODE_SENT.equals(status))) {
            status = PaymentIntent.CANCELLED.equals(intent.getStatus()) ? BankPayment.CANCELLED
                    : PaymentIntent.EXPIRED.equals(intent.getStatus()) ? BankPayment.EXPIRED : BankPayment.FAILED;
        }
        if (BankPayment.DEBIT_PENDING.equals(status)) {
            // Existing Angular page already knows how to display CHECK_BANK.
            status = BankPayment.CHECK_BANK;
            if (message == null) {
                message = "The bank debit is being processed or its result is not yet known. "
                        + "Do not pay again; staff will reconcile it if necessary.";
            }
        }
        if (BankPayment.CHECK_BANK.equals(status) && message == null) {
            message = "Your bank did not confirm the payment in time. Please do not pay again: we are checking with the bank "
                    + "and will update your order (usually the same working day).";
        }
        return new BankView(intent.getReference(), intent.getOrderId(), intent.getAmount(), status,
                bp == null ? null : bp.getBankCode(), bp == null ? null : bp.getBankName(), bp == null ? null : bp.getAccountLast4(),
                bp == null || !BankPayment.CODE_SENT.equals(status) ? null : bp.getCodeExpiresAt(),
                bp == null ? MAX_WRONG : Math.max(0, MAX_WRONG - bp.getWrongCodes()),
                bp == null ? MAX_CODES : Math.max(0, MAX_CODES - bp.getCodesSent()),
                test, test ? TestBankGatewayClient.TEST_CODE : null, bp == null ? null : bp.getBankReference(),
                message != null ? message : bp != null && bp.getFailureReason() != null ? bp.getFailureReason()
                        : intent.isFinal() && !PaymentIntent.PAID.equals(intent.getStatus()) ? intent.getMessage() : null);
    }

    private void record(String reference, String step, String result, String message) {
        PaymentEvent e = new PaymentEvent();
        e.setIntentReference(reference);
        e.setStep(step);
        e.setResult(result);
        e.setMessage(cut(message));
        e.setActor(CurrentUser.email());
        e.setAt(Instant.now());
        events.save(e);
    }

    private static void requireVerifier() {
        if (!CurrentUser.has("payments.verify")) {
            throw new AccessDeniedException("Only staff who check payments can do this.");
        }
    }

    private BankGatewayClient client() {
        BankGatewayClient c = clients.getIfAvailable();
        if (c == null) {
            throw new IllegalStateException("Online payment is not available right now. Please try again in a little while.");
        }
        return c;
    }

    private static String cut(String value) {
        return value == null || value.length() <= 300 ? value : value.substring(0, 300);
    }
}
