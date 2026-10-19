package com.api.inventory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

import java.time.Instant;

/**
 * Every step of an online payment, as it happened: code requested, code sent, wrong code, paid, refused...
 * Used to answer customers ("what happened to my payment?") and to match our records with the gateway's.
 * Never holds an account number or a one-time code.
 */
@Entity
@Table(name = "payment_events", indexes = @Index(name = "idx_payevent_ref", columnList = "intentReference, at"))
@Getter
@Setter
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String intentReference;

    /** For example CODE_REQUESTED, CODE_SENT, WRONG_CODE, PAID, REFUSED, CANCELLED. */
    @Column(nullable = false, length = 30)
    private String step;

    /** OK or the gateway's / bank's answer code. */
    @Column(length = 30)
    private String result;

    @Column(length = 300)
    private String message;

    @Column(length = 120)
    private String actor;

    @Column(name = "provider_reference", length = 80)
    private String providerReference;

    @Column(name = "reported_amount", precision = 12, scale = 2)
    private BigDecimal reportedAmount;

    @Column(nullable = false)
    private Instant at;
}
