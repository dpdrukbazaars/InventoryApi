package com.api.inventory;

import com.api.inventory.service.PaymentReferenceClaimService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the reference-claim race against local MySQL, never a real bank. */
@SpringBootTest(properties = {
        "app.payments.bank.mode=test",
        "app.payments.sandbox.enabled=false"
})
@ActiveProfiles("mysql-integration")
@EnabledIfEnvironmentVariable(named = "INVENTORY_TEST_DB_PASSWORD", matches = ".+")
class MysqlPaymentReferenceClaimConcurrencyTest {

    @Autowired PaymentReferenceClaimService claims;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private boolean claim(String provider, String reference, String attempt) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(status -> claims.claimOrOwn(provider, reference, attempt)));
    }

    @Test
    void competingAttemptsCannotBothOwnOneReferenceInMySql() throws Exception {
        // A hard guard against accidentally using a production database.
        assertEquals("inventory_session_test",
                jdbc.queryForObject("SELECT DATABASE()", String.class),
                "Refusing to write outside the local integration-test database");

        String provider = "BANK";
        String reference = "TEST-CLAIM-" + UUID.randomUUID().toString().substring(0, 24);
        String intentA = "PI-A-" + UUID.randomUUID().toString().substring(0, 24);
        String intentB = "PI-B-" + UUID.randomUUID().toString().substring(0, 24);
        CountDownLatch start = new CountDownLatch(1);

        try {
            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<Boolean> first = pool.submit(() -> {
                    start.await();
                    return claim(provider, reference, intentA);
                });
                Future<Boolean> second = pool.submit(() -> {
                    start.await();
                    return claim(provider, reference, intentB);
                });
                start.countDown();

                boolean a = first.get(20, TimeUnit.SECONDS);
                boolean b = second.get(20, TimeUnit.SECONDS);
                assertNotEquals(a, b, "Exactly one attempt may own the reference");

                String winner = a ? intentA : intentB;
                String loser = a ? intentB : intentA;
                assertTrue(claim(provider, reference, winner), "The winner should be idempotent");
                assertFalse(claim(provider, reference, loser), "The loser must not claim it later");
            }
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_reference_claims WHERE provider=? AND provider_reference=?",
                    Long.class, provider, reference));
        } finally {
            // Remove only this test's uniquely generated claim. Preserve all other data.
            jdbc.update("DELETE FROM payment_reference_claims WHERE provider=? AND provider_reference=?",
                    provider, reference);
        }
    }
}
