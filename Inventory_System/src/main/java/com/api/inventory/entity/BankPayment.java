package com.api.inventory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment from the customer's own bank account through the payment gateway (RMA Payment Gateway):
 * the customer picks their bank and enters their account number, the bank sends a one-time code to the phone
 * registered with that account, and the customer enters it to approve the debit.
 *
 * Stored: the bank, the LAST 4 digits of the account, the gateway's transaction number and what happened.
 * Never stored: the full account number or the one-time code.
 *
 *   STARTED -> CODE_SENT -> DEBIT_PENDING -> PAID | CHECK_BANK
 *                   \-----> FAILED (wrong code 3 times, refused by the bank) | CANCELLED | EXPIRED
 */
@Entity
@Table(name = "bank_payments", indexes = {
        @Index(name = "idx_bankpay_intent", columnList = "intentReference", unique = true),
        @Index(name = "idx_bankpay_status", columnList = "status")
})
@Getter
@Setter
public class BankPayment {

    public static final String STARTED = "STARTED";
    public static final String CODE_SENT = "CODE_SENT";
    public static final String PAID = "PAID";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
    /** The bank was asked to take the money but its answer never came: staff check with the bank before anything else. */
    public static final String CHECK_BANK = "CHECK_BANK";
    /** Durable reservation made before sending a debit request to the bank. */
    public static final String DEBIT_PENDING = "DEBIT_PENDING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The online payment attempt (payment_intents.reference) this belongs to. */
    @Column(nullable = false, length = 40)
    private String intentReference;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 12)
    private String bankCode;

    @Column(length = 80)
    private String bankName;

    /** Only the last 4 digits, for the receipt and for staff ("account ending 1234"). */
    @Column(length = 4)
    private String accountLast4;

    /** The gateway's own transaction number (used for every later step and for reconciliation). */
    @Column(length = 80)
    private String gatewayTransactionId;

    /** The journal (authorisation) number the customer's bank gave for the debit: what the bank statement shows. */
    @Column(length = 80)
    private String bankReference;

    @Column(nullable = false, length = 16)
    private String status = STARTED;

    private Instant codeSentAt;
    private Instant codeExpiresAt;

    /** Written and committed BEFORE sending the debit request. */
    private Instant debitRequestedAt;

    /** How many times a code was sent (limited, so nobody can flood the customer's phone). */
    @Column(nullable = false)
    private int codesSent = 0;

    /** Wrong codes entered for the current code (3 = the payment stops). */
    @Column(nullable = false)
    private int wrongCodes = 0;

    @Column(length = 300)
    private String failureReason;

    /** True when made in test mode (no bank contacted, no money moved). */
    @Column(nullable = false)
    private boolean testMode;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;
}
