package com.api.inventory;

import com.api.inventory.service.PaymentReferenceClaimService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real database uniqueness/transaction regression; no real bank calls. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:claimrace;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "app.payments.bank.mode=test",
        "app.payments.sandbox.enabled=false",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class PaymentReferenceClaimConcurrencyTest {
    @Autowired PaymentReferenceClaimService claims;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private boolean claim(String provider, String ref, String attempt) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(status -> claims.claimOrOwn(provider, ref, attempt)));
    }

    @Test
    void competingIntentsCannotBothOwnSameProviderReference() throws Exception {
        String bankReference = "RACE-JOURNAL-01";
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> {
                start.await();
                return claim("BANK", bankReference, "PI-RACE-A");
            });
            Future<Boolean> second = pool.submit(() -> {
                start.await();
                return claim("BANK", bankReference, "PI-RACE-B");
            });
            start.countDown();
            boolean a = first.get(15, TimeUnit.SECONDS);
            boolean b = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(a, b, "one attempt must lose the provider-reference claim");
        }
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_reference_claims "
                        + "WHERE provider='BANK' AND provider_reference=?",
                Long.class, bankReference));
    }

    @Test
    void originalAttemptCanRetryButDifferentAttemptCannotTakeItsClaim() {
        assertTrue(claim("BANK", "REPEAT-JOURNAL-01", "PI-OWNER"));
        assertTrue(claim("BANK", "REPEAT-JOURNAL-01", "PI-OWNER"));
        assertFalse(claim("BANK", "REPEAT-JOURNAL-01", "PI-OTHER"));
    }

    @Test
    void independentProvidersMayIssueSameReference() {
        assertTrue(claim("BANK", "PROVIDER-JOURNAL-01", "PI-BANK"));
        assertTrue(claim("SANDBOX", "PROVIDER-JOURNAL-01", "PI-SANDBOX"));
    }
}
