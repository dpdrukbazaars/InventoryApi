-- Add the provider reference only if it is missing.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE payment_events ADD COLUMN provider_reference VARCHAR(80) NULL',
        'SELECT 1')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'payment_events'
      AND COLUMN_NAME = 'provider_reference'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- Add the reported amount only if it is missing.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE payment_events ADD COLUMN reported_amount DECIMAL(12,2) NULL',
        'SELECT 1')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'payment_events'
      AND COLUMN_NAME = 'reported_amount'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- Create the lookup index only if it is missing.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_payevent_provider_ref ON payment_events (provider_reference)',
        'SELECT 1')
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'payment_events'
      AND INDEX_NAME = 'idx_payevent_provider_ref'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;