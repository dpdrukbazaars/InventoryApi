package com.api.inventory.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Claims are written in the SAME transaction as the receipt and payment intent.
 * The database unique key, not a read-then-write check, arbitrates concurrent
 * successes for different intents. Requires MySQL or H2's ability to continue a
 * transaction after a handled unique-key violation.
 */
@Service
public class PaymentReferenceClaimService {
    private final JdbcTemplate jdbc;

    public PaymentReferenceClaimService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when the given intent is the owner (new or already claimed);
     * false when another intent owns this provider/reference combination.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimOrOwn(String provider, String providerReference, String intentReference) {
        if (provider == null || provider.isBlank() || provider.length() > 20
                || providerReference == null || providerReference.isBlank() || providerReference.length() > 80
                || intentReference == null || intentReference.isBlank() || intentReference.length() > 40) {
            throw new IllegalArgumentException("Invalid provider transaction claim.");
        }
        try {
            jdbc.update("INSERT INTO payment_reference_claims "
                            + "(provider, provider_reference, intent_reference, claimed_at) VALUES (?, ?, ?, ?)",
                    provider, providerReference, intentReference, Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException conflict) {
            // Locking read sees the winning committed row even under MySQL REPEATABLE READ.
            List<String> owners = jdbc.query(
                    "SELECT intent_reference FROM payment_reference_claims "
                            + "WHERE provider = ? AND provider_reference = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getString(1), provider, providerReference);
            if (owners.size() != 1) {
                throw new IllegalStateException("Cannot reconcile the provider transaction claim.", conflict);
            }
            return intentReference.equals(owners.get(0));
        }
    }
}
