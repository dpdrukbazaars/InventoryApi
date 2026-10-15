
package com.api.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-integration")
@EnabledIfEnvironmentVariable(
        named = "INVENTORY_TEST_DB_PASSWORD",
        matches = ".+"
)
class MysqlSessionConcurrencyTest {

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> repository;

    @Test
    void concurrentSessionUpdates() throws Exception {

        var session = repository.createSession();
        String sessionId = session.getId();

        // Save the session before concurrent access.
        saveSession(session);

        int workers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();

            for (int i = 0; i < workers; i++) {
                final int worker = i;

                results.add(executor.submit(() -> {
                    start.await();

                    var current = repository.findById(sessionId);
                    assertNotNull(current);

                    current.setAttribute("testAttribute", "worker-" + worker);
                    saveSession(current);

                    return null;
                }));
            }

            start.countDown();

            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }

            var saved = repository.findById(sessionId);
            assertNotNull(saved);
            assertNotNull(saved.getAttribute("testAttribute"));

        } finally {
            executor.shutdownNow();
            repository.deleteById(sessionId);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void saveSession(Session session) {
        ((FindByIndexNameSessionRepository) repository).save(session);
    }
}
