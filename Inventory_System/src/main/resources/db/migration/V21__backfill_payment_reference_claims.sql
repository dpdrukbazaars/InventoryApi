-- V21: backfill owners for historical successful payment references.
-- V20 has already run in the local test database. Do not edit V20 after Flyway records it.
-- Mark collisions as LEGACY_CONFLICT so no attempt is automatically treated as the owner.
INSERT INTO payment_reference_claims
    (provider, provider_reference, intent_reference, claimed_at)
SELECT reports.provider,
       reports.provider_reference,
       CASE WHEN COUNT(DISTINCT reports.intent_reference) = 1
            THEN MIN(reports.intent_reference)
            ELSE 'LEGACY_CONFLICT' END,
       UTC_TIMESTAMP(6)
FROM (
    SELECT provider, TRIM(provider_reference) AS provider_reference,
           reference AS intent_reference
    FROM payment_intents
    WHERE status = 'PAID' AND provider_reference IS NOT NULL
          AND TRIM(provider_reference) <> ''
    UNION ALL
    SELECT pi.provider, TRIM(pe.provider_reference), pe.intent_reference
    FROM payment_events pe
    JOIN payment_intents pi ON pi.reference = pe.intent_reference
    WHERE pe.step = 'SUCCESS_RECEIPT' AND pe.provider_reference IS NOT NULL
          AND TRIM(pe.provider_reference) <> ''
    UNION ALL
    SELECT pi.provider, TRIM(bp.bank_reference), bp.intent_reference
    FROM bank_payments bp
    JOIN payment_intents pi ON pi.reference = bp.intent_reference
    WHERE bp.status = 'PAID' AND bp.bank_reference IS NOT NULL
          AND TRIM(bp.bank_reference) <> ''
) reports
WHERE reports.provider IS NOT NULL AND TRIM(reports.provider) <> ''
GROUP BY reports.provider, reports.provider_reference
ON DUPLICATE KEY UPDATE
    intent_reference = CASE
        WHEN payment_reference_claims.intent_reference = VALUES(intent_reference)
            THEN payment_reference_claims.intent_reference
        ELSE 'LEGACY_CONFLICT'
    END;
