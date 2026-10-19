package com.api.inventory.service;

import com.api.inventory.entity.Order;
import com.api.inventory.entity.Payment;
import com.api.inventory.entity.PaymentIntent;
import com.api.inventory.entity.PaymentEvent;
import com.api.inventory.entity.BankPayment;
import com.api.inventory.repository.BankPaymentRepository;
import com.api.inventory.repository.OrderRepository;
import com.api.inventory.repository.PaymentIntentRepository;
import com.api.inventory.repository.PaymentEventRepository;
import com.api.inventory.repository.PaymentRepository;
import com.api.inventory.security.CurrentUser;
import com.api.inventory.service.payments.PaymentGateway;
import com.api.inventory.service.payments.SandboxGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * Paying an order online.
 *
 *   1. start: the customer picks a gateway; we record an attempt (PaymentIntent) for the order's real total
 *      and send them to the gateway's page.
 *   2. the gateway tells us the result (callback, checked by the gateway's signature; or the test page).
 *   3. complete: a successful, correct-amount payment is recorded and the order is confirmed automatically,
 *      exactly as if staff had verified a bank transfer: stock is taken, sellers are told to pack, the customer
 *      is told. If something is off (wrong amount, order cancelled meanwhile, stock ran out), the money stays
 *      recorded and staff are told to sort it out; the customer is never charged twice for one order.
 *
 * The amount always comes from the order on the server, never from the browser.
 */
@Service
public class OnlinePaymentService {

    private static final Logger log = LoggerFactory.getLogger(OnlinePaymentService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** An unfinished attempt is closed after this long. */
    private static final Duration ATTEMPT_LIFETIME = Duration.ofHours(1);

    public record Option(String code, String label) {
    }

    public record StartResult(String reference, String redirectUrl) {
    }

    public record IntentView(String reference, Long orderId, BigDecimal amount, String currency, String provider,
                             String status, String message, String providerReference, Instant createdAt, Instant completedAt) {
    }

    private final PaymentIntentRepository intents;
    private final PaymentEventRepository events;
    private final PaymentReferenceClaimService referenceClaims;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final BankPaymentRepository bankPayments;
    private final OrderService orderService;
    private final List<PaymentGateway> gateways;
    private final NotificationService notify;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    public OnlinePaymentService(PaymentIntentRepository intents, OrderRepository orders, PaymentRepository payments,
                                BankPaymentRepository bankPayments, PaymentEventRepository events,
                                PaymentReferenceClaimService referenceClaims, OrderService orderService, List<PaymentGateway> gateways, NotificationService notify,
                                AuditService audit, PlatformTransactionManager transactions) {
        this.intents = intents;
        this.events = events;
        this.referenceClaims = referenceClaims;
        this.orders = orders;
        this.payments = payments;
        this.bankPayments = bankPayments;
        this.orderService = orderService;
        this.gateways = gateways;
        this.notify = notify;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The online ways to pay that are switched on (empty = only bank transfer). */
    public List<Option> options() {
        return gateways.stream().filter(PaymentGateway::enabled).map(g -> new Option(g.code(), g.label())).toList();
    }

    @Transactional
    public StartResult start(Long orderId, String providerCode) {
        PaymentGateway gateway = gateway(providerCode);
        Order order = orders.findByIdForPaymentUpdate(
                orderId == null ? -1L : orderId
        ).orElseThrow(() ->
                new IllegalStateException("Order not found.")
        );
        String me = CurrentUser.email();
        if (me == null || order.getCustomerEmail() == null || !order.getCustomerEmail().equalsIgnoreCase(me)) {
            throw new AccessDeniedException("You can only pay for your own orders.");
        }
        if ("CANCELLED".equals(order.getOrderStatus())) {
            throw new IllegalStateException("This order was cancelled and cannot be paid.");
        }
        if (!"PENDING".equals(order.getPaymentStatus())) {
            throw new IllegalStateException("This order is already paid or its payment is being checked.");
        }
        Optional<Payment> sent = payments.findByOrderId(orderId);
        if (sent.isPresent()) {
            throw new IllegalStateException("You already sent a payment for this order (journal " + sent.get().getJournalNumber()
                    + "). We are checking it.");
        }
        BigDecimal amount = order.getTotalAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalStateException("This order has no amount to pay.");
        }

        // pressing Pay again uses the same open attempt, so the gateway never sees two live payments for one order
        Instant now = Instant.now();
        for (PaymentIntent open : intents.findByOrderIdOrderByIdDesc(orderId)) {
            if (bankPayments.existsByIntentReferenceAndStatusIn(open.getReference(),
                    List.of(BankPayment.DEBIT_PENDING, BankPayment.CHECK_BANK, BankPayment.PAID))) {
                throw new IllegalStateException("A bank debit for this order is being processed or checked. "
                        + "Please do not pay again until it has been reconciled.");
            }
            if (PaymentIntent.CREATED.equals(open.getStatus())) {
                boolean reusable = open.getProvider().equals(gateway.code()) && open.getAmount().compareTo(amount) == 0
                        && open.getCreatedAt().isAfter(now.minus(ATTEMPT_LIFETIME).plus(Duration.ofMinutes(10)));
                if (reusable) {
                    return new StartResult(open.getReference(), gateway.startUrl(open));
                }
                open.setStatus(PaymentIntent.CANCELLED);
                open.setMessage("Replaced by a new attempt.");
                open.setCompletedAt(now);
                intents.save(open);
                bankPayments.closeOpen(open.getReference(), BankPayment.CANCELLED, now);
            }
        }

        PaymentIntent intent = new PaymentIntent();
        intent.setReference(newReference());
        intent.setOrderId(orderId);
        intent.setCustomerEmail(order.getCustomerEmail());
        intent.setAmount(amount);
        intent.setProvider(gateway.code());
        intent.setCreatedAt(now);
        intents.save(intent);
        return new StartResult(intent.getReference(), gateway.startUrl(intent));
    }

    /** The customer (or staff checking payments) looks at an attempt. */
    public IntentView status(String reference) {
        PaymentIntent intent = intents.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new IllegalStateException("Payment not found."));
        requireOwnerOrStaff(intent);
        return view(intent);
    }

    /** The test page's buttons. Only while the test gateway is switched on. */
    public IntentView completeSandbox(String reference, String outcome) {
        PaymentGateway sandbox = gateway(SandboxGateway.CODE);
        PaymentIntent intent = intents.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new IllegalStateException("Payment not found."));
        requireOwnerOrStaff(intent);
        if (!sandbox.code().equals(intent.getProvider())) {
            throw new IllegalStateException("This is not a test payment.");
        }
        String result = outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT);
        switch (result) {
            case "PAID" -> complete(new PaymentGateway.Result(intent.getReference(), true, "TEST-" + newReference().substring(3),
                    intent.getAmount(), "Test payment"));
            case "FAILED" -> complete(new PaymentGateway.Result(intent.getReference(), false, null, null, "The test bank refused the payment."));
            case "CANCELLED" -> cancel(intent.getReference());
            default -> throw new IllegalArgumentException("Choose PAID, FAILED or CANCELLED.");
        }
        return status(reference);
    }

    /** A gateway's server-to-server message. Ignored (and logged) when its signature is not right. */
    public void callback(String providerCode, Map<String, String> params) {
        PaymentGateway gateway = gateway(providerCode);
        PaymentGateway.Result result = gateway.verifyCallback(params).orElseThrow(() -> {
            log.warn("Rejected a {} payment message that failed its check", providerCode);
            return new AccessDeniedException("The payment message could not be verified.");
        });
        complete(result);
    }

    /** The customer left the gateway without paying. */
    @Transactional
    public void cancel(String reference) {
        intents.findByReferenceForUpdate(reference).ifPresent(intent -> {
            if (bankPayments.existsByIntentReferenceAndStatusIn(intent.getReference(),
                    List.of(BankPayment.DEBIT_PENDING, BankPayment.CHECK_BANK, BankPayment.PAID))) {
                throw new IllegalStateException("A bank debit may have completed. This attempt cannot be cancelled "
                        + "until the bank confirms the result.");
            }
            if (!intent.isFinal()) {
                intent.setStatus(PaymentIntent.CANCELLED);
                intent.setMessage("You cancelled the payment.");
                intent.setCompletedAt(Instant.now());
                intents.save(intent);
                bankPayments.closeOpen(intent.getReference(), BankPayment.CANCELLED, intent.getCompletedAt());
            }
        });
    }

    /**
     * A verified gateway result. Each new bank receipt is preserved as a PaymentEvent.
     * The intent lock serializes callbacks for the same attempt. Different successful
     * bank references never overwrite the first payment or confirm the order again.
     */
    public void complete(PaymentGateway.Result result) {
        Long orderToConfirm = tx.execute(status -> {
            PaymentIntent intent = intents.findByReferenceForUpdate(result.reference())
                    .orElseThrow(() -> new IllegalStateException("Payment not found."));

            // Only verified success results get recorded as bank-receipt evidence.
            // A callback is verified by callback(), or supplied by the trusted bank service.
            String providerReference = normalizeReference(result.providerReference());
            // Claim atomically before recording evidence. A conflicting bank report is
            // retained in payment_events but must never confirm a different order.
            boolean differentAttemptSameBankReference = result.paid() && providerReference != null
                    && !referenceClaims.claimOrOwn(intent.getProvider(), providerReference, intent.getReference());
            boolean newReceipt = result.paid() && recordSuccessReceiptIfNew(intent, result);

            if (intent.isFinal()) {
                if (PaymentIntent.PAID.equals(intent.getStatus())) {
                    // A second *distinct* success can represent a second debit. Never
                    // overwrite the first receipt or confirm the order a second time.
                    String incomingReference = normalizeReference(result.providerReference());
                    String originalReference = normalizeReference(intent.getProviderReference());
                    boolean differentBankReference = !Objects.equals(incomingReference, originalReference);
                    boolean amountChanged = result.amount() != null
                            && result.amount().compareTo(intent.getAmount()) != 0;
                    if ((newReceipt && (differentBankReference || differentAttemptSameBankReference
                            || incomingReference == null)) || amountChanged) {
                        audit.record("ONLINE_PAYMENT_ADDITIONAL_SUCCESS", "order #" + intent.getOrderId(),
                                "Intent " + intent.getReference() + ", new bank reference: " + incomingReference
                                        + ", amount: " + result.amount());
                        staffMustCheck(intent,
                                "An additional successful bank result was reported. Bank reference: "
                                        + incomingReference + ", reported amount: " + result.amount()
                                        + ". Verify whether this is another debit and whether a refund is needed.");
                    }
                    return null;
                }

                // Successful debit after cancellation/expiry/failure: retain the receipt,
                // but leave the order for staff verification instead of confirming it.
                if (result.paid()) {
                    String previousStatus = intent.getStatus();
                    intent.setStatus(PaymentIntent.PAID);
                    intent.setProviderReference(cut(result.providerReference(), 80));
                    intent.setCompletedAt(Instant.now());
                    intent.setMessage(cut("Late successful payment after " + previousStatus
                            + ". STAFF RECONCILIATION REQUIRED.", 300));
                    intents.save(intent);

                    Payment existing = payments.findByOrderId(intent.getOrderId()).orElse(null);
                    if (existing == null && result.amount() != null && result.amount().signum() > 0) {
                        Payment receipt = new Payment();
                        receipt.setOrderId(intent.getOrderId());
                        receipt.setPaymentMethod("online");
                        receipt.setAmount(result.amount());
                        receipt.setStatus("pending");
                        receipt.setPaymentDate(LocalDateTime.now());
                        receipt.setJournalNumber(cut("ONLINE " + intent.getProvider() + " "
                                + (result.providerReference() == null
                                ? intent.getReference() : result.providerReference()), 250));
                        payments.save(receipt);
                    }

                    audit.record("ONLINE_PAYMENT_LATE_SUCCESS", "order #" + intent.getOrderId(),
                            "Previous status: " + previousStatus + ", payment reference: "
                                    + intent.getReference() + ", bank reference: "
                                    + result.providerReference() + ", reported amount: " + result.amount());
                    staffMustCheck(intent,
                            "A successful payment arrived after " + previousStatus
                                    + ". Bank reference: " + result.providerReference()
                                    + ", amount: Nu. " + result.amount()
                                    + ". Check the bank, existing payments, order status and refund requirements.");
                }
                return null;
            }

            Instant now = Instant.now();
            intent.setCompletedAt(now);
            intent.setProviderReference(cut(result.providerReference(), 80));
            if (!result.paid()) {
                intent.setStatus(PaymentIntent.FAILED);
                intent.setMessage(cut(result.message() == null
                        ? "The payment did not go through." : result.message(), 300));
                intents.save(intent);
                return null;
            }

            intent.setStatus(PaymentIntent.PAID);
            intent.setMessage(differentAttemptSameBankReference
                    ? "Bank reference conflict. STAFF RECONCILIATION REQUIRED."
                    : "Paid");
            intents.save(intent);
            audit.record("ONLINE_PAYMENT", "order #" + intent.getOrderId(),
                    intent.getProvider() + " Nu. " + result.amount() + ", ref "
                            + intent.getReference() + " / " + result.providerReference());

            Order order = orders.findById(intent.getOrderId()).orElse(null);
            boolean amountRight = result.amount() != null
                    && result.amount().compareTo(intent.getAmount()) == 0;
            Payment existing = payments.findByOrderId(intent.getOrderId()).orElse(null);
            // Never rewrite an earlier payment record, even if it was not confirmed.
            if (existing == null) {
                Payment payment = new Payment();
                payment.setOrderId(intent.getOrderId());
                payment.setPaymentMethod("online");
                payment.setAmount(result.amount() == null ? intent.getAmount() : result.amount());
                payment.setStatus("pending");
                payment.setPaymentDate(LocalDateTime.now());
                payment.setJournalNumber(cut("ONLINE " + intent.getProvider() + " "
                        + (result.providerReference() == null
                        ? intent.getReference() : result.providerReference()), 250));
                payments.save(payment);
            }

            String problem = null;
            if (order == null) {
                problem = "the order no longer exists";
            } else if (differentAttemptSameBankReference) {
                problem = "the bank reference is also recorded for another payment attempt";
            } else if (existing != null) {
                problem = "a payment record already exists for this order: do not overwrite it";
            } else if (normalizeReference(result.providerReference()) == null) {
                problem = "the gateway did not supply a usable bank reference: staff must check the bank";
            } else if (!amountRight) {
                problem = "the gateway reported Nu. " + result.amount()
                        + " but this attempt expected Nu. " + intent.getAmount();
            } else if ("CANCELLED".equals(order.getOrderStatus())) {
                problem = "the order was cancelled before the payment arrived: check for a refund";
            } else if (!"PENDING".equals(order.getPaymentStatus())) {
                problem = "the order was already paid: check whether a refund is needed";
            }
            if (problem != null) {
                staffMustCheck(intent, problem);
                return null;
            }
            order.setPaymentVerifiedBy("online:" + intent.getProvider());
            orders.save(order);
            return order.getOrderId();
        });

        if (orderToConfirm != null) {
            try {
                newTx.executeWithoutResult(s -> orderService.confirmPayment(orderToConfirm));
            } catch (RuntimeException e) {
                PaymentIntent intent = intents.findByReference(result.reference()).orElse(null);
                if (intent != null) {
                    tx.executeWithoutResult(s -> staffMustCheck(intent,
                            "it was paid but could not be confirmed: " + e.getMessage()));
                }
            }
        }
    }

    /**
     * Append each distinct successful bank result in the same transaction as the
     * PaymentIntent update. The intent row is already locked by complete().
     * For missing bank references we can preserve one receipt per attempt only;
     * staff must confirm the actual number of bank debits independently.
     */
    private boolean recordSuccessReceiptIfNew(PaymentIntent intent, PaymentGateway.Result result) {
        String incomingRef = normalizeReference(result.providerReference());
        boolean alreadyRecorded = events.findByIntentReferenceOrderByAtAsc(intent.getReference())
                .stream().anyMatch(e -> "SUCCESS_RECEIPT".equals(e.getStep())
                        && Objects.equals(normalizeReference(e.getProviderReference()), incomingRef));
        if (alreadyRecorded) {
            return false;
        }

        PaymentEvent event = new PaymentEvent();
        event.setIntentReference(intent.getReference());
        event.setStep("SUCCESS_RECEIPT");
        event.setResult("OK");
        event.setActor(cut("gateway:" + intent.getProvider(), 120));
        event.setProviderReference(cut(incomingRef, 80));
        event.setReportedAmount(result.amount());
        event.setAt(Instant.now());
        event.setMessage(incomingRef == null
                ? "Gateway reported successful payment without a bank reference; staff verification needed."
                : "Gateway reported successful payment; retain for bank reconciliation.");
        events.save(event);
        return true;
    }

    private static String normalizeReference(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        // Never claim a truncated identifier: different original references
        // could otherwise collide at character 80.
        return trimmed.length() > 80 ? null : trimmed;
    }

    /** Every 10 minutes: attempts nobody finished within an hour are closed. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    @Transactional
    public void expireOld() {
        Instant now = Instant.now();
        intents.expireOlderThan(now.minus(ATTEMPT_LIFETIME), now);
        bankPayments.expireClosedAttempts(now);
    }

    // ================= Helpers =================

    private void staffMustCheck(PaymentIntent intent, String problem) {
        log.warn("Online payment {} for order #{} needs staff: {}", intent.getReference(), intent.getOrderId(), problem);
        notify.withPermission("payments.verify", new NotificationService.Note("ONLINE_PAYMENT_PROBLEM",
                "Check online payment for order #" + intent.getOrderId(),
                "Payment " + intent.getReference() + " (Nu. " + intent.getAmount() + "): " + problem + ".", "/order-verification"), true);
    }

    private PaymentGateway gateway(String code) {
        String wanted = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return gateways.stream().filter(g -> g.code().equals(wanted) && g.enabled()).findFirst()
                .orElseThrow(() -> new IllegalStateException("That way to pay is not available. Please choose another."));
    }

    private void requireOwnerOrStaff(PaymentIntent intent) {
        String me = CurrentUser.email();
        boolean owner = me != null && me.equalsIgnoreCase(intent.getCustomerEmail());
        if (!owner && !CurrentUser.has("payments.verify")) {
            throw new AccessDeniedException("This is not your payment.");
        }
    }

    private static IntentView view(PaymentIntent i) {
        return new IntentView(i.getReference(), i.getOrderId(), i.getAmount(), i.getCurrency(), i.getProvider(), i.getStatus(),
                i.getMessage(), i.getProviderReference(), i.getCreatedAt(), i.getCompletedAt());
    }

    private static String newReference() {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        return "PI-" + HexFormat.of().withUpperCase().formatHex(b);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
