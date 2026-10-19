package com.api.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One authoritative owner for a verified successful provider transaction reference.
 * Conflicting reports are still recorded separately in payment_events.
 */
@Entity
@Table(name = "payment_reference_claims",
        indexes = @Index(name = "idx_payment_claim_intent", columnList = "intent_reference"),
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_claim_provider_reference",
                columnNames = {"provider", "provider_reference"}))
@Getter
@Setter
public class PaymentReferenceClaim {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_reference", nullable = false, length = 80)
    private String providerReference;

    @Column(name = "intent_reference", nullable = false, length = 40)
    private String intentReference;

    @Column(name = "claimed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant claimedAt;
}
