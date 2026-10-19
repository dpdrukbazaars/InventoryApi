-- A bank transaction reference can be claimed by only one payment attempt
-- within a provider. Conflicting callbacks remain in payment_events.

CREATE TABLE payment_reference_claims (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(20) NOT NULL,
    provider_reference VARCHAR(80) NOT NULL,
    intent_reference VARCHAR(40) NOT NULL,
    claimed_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT uk_payment_claim_provider_reference
        UNIQUE (provider, provider_reference),

    INDEX idx_payment_claim_intent (intent_reference)
);
