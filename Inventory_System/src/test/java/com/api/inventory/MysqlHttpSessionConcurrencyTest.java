
package com.api.inventory;

import com.api.inventory.entity.User;
import com.api.inventory.repository.RoleRepository;
import com.api.inventory.repository.UserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/inventory_session_test?serverTimezone=UTC",
        "spring.datasource.username=inventory_test"
})
@ActiveProfiles("mysql-integration")
@EnabledIfEnvironmentVariable(
        named = "INVENTORY_TEST_DB_PASSWORD",
        matches = ".+"
)
class MysqlHttpSessionConcurrencyTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    Filter sessionFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter securityChain;

    @Autowired
    UserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentRequestsUsingSameLoginCookie() throws Exception {
        // This test must NEVER modify the normal application database.
        assertEquals(
                "inventory_session_test",
                jdbc.queryForObject("SELECT DATABASE()", String.class)
        );

        String email = "session-" + UUID.randomUUID() + "@example.test";
        String phone = String.format(
                "17%06d", ThreadLocalRandom.current().nextInt(1_000_000)
        );
        String password = "TestPassword123!";

        User user = new User();
        user.setName("Session Test User");
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(encoder.encode(password));
        user.setActive(true);
        user.setRole(roles.findByName("USER").orElseThrow());

        User savedUser = users.save(user);

        try {
            MockMvc browser = MockMvcBuilders
                    .webAppContextSetup(context)
                    .addFilters(sessionFilter, securityChain)
                    .build();

            // Log in using the actual authentication endpoint.
            var loginResponse = browser.perform(
                            post("/api/auth/login")
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"email\":\"" + email
                                                    + "\",\"password\":\"" + password + "\"}"
                                    )
                    )
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse();

            String loginCookieHeader = loginResponse.getHeader("Set-Cookie");
            assertNotNull(loginCookieHeader, "Login must issue a SESSION cookie");
            assertTrue(loginCookieHeader.startsWith("SESSION="), "Expected SESSION cookie");

            String initialSessionId = sessionIdFromSetCookie(loginCookieHeader);
            System.out.println("Login completed successfully.");

            // First authenticated request. The server may rotate the session ID.
            var firstResponse = browser.perform(
                            get("/api/auth/me")
                                    .cookie(new Cookie("SESSION", initialSessionId))
                    )
                    .andReturn()
                    .getResponse();

            int firstStatus = firstResponse.getStatus();
            String firstCookieHeader = firstResponse.getHeader("Set-Cookie");

            // Simulate a browser accepting an updated Set-Cookie header.
            String activeSessionId = initialSessionId;
            if (firstCookieHeader != null && firstCookieHeader.startsWith("SESSION=")) {
                activeSessionId = sessionIdFromSetCookie(firstCookieHeader);
            }

            System.out.println("First sequential status: " + firstStatus);
            System.out.println("First response issued a cookie: " + (firstCookieHeader != null));
            System.out.println("Session cookie rotated: " + !activeSessionId.equals(initialSessionId));

            Integer originalSessionRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM spring_session WHERE SESSION_ID = ?",
                    Integer.class,
                    initialSessionId
            );
            System.out.println("Original session rows after first request: " + originalSessionRows);

            assertEquals(200, firstStatus, "First authenticated request must succeed");

            // Second sequential request must use the most recent cookie.
            var secondResponse = browser.perform(
                            get("/api/auth/me")
                                    .cookie(new Cookie("SESSION", activeSessionId))
                    )
                    .andReturn()
                    .getResponse();

            int secondStatus = secondResponse.getStatus();
            System.out.println("Second sequential status: " + secondStatus);
            assertEquals(200, secondStatus, "Refreshed session must remain authenticated");

            // Handle another Set-Cookie, if the second response issues one.
            String secondCookieHeader = secondResponse.getHeader("Set-Cookie");
            if (secondCookieHeader != null && secondCookieHeader.startsWith("SESSION=")) {
                activeSessionId = sessionIdFromSetCookie(secondCookieHeader);
            }

            // A local variable captured by a lambda must be final/effectively final.
            final String concurrentSessionId = activeSessionId;
            final int workers = 10;

            ExecutorService executor = Executors.newFixedThreadPool(workers);
            CountDownLatch start = new CountDownLatch(1);

            try {
                List<Future<Integer>> results = new ArrayList<>();

                for (int i = 0; i < workers; i++) {
                    results.add(executor.submit(() -> {
                        start.await();
                        return browser.perform(
                                        get("/api/auth/me")
                                                .cookie(new Cookie("SESSION", concurrentSessionId))
                                )
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }));
                }

                System.out.println("Starting " + workers + " concurrent HTTP requests...");
                start.countDown();

                int successfulRequests = 0;
                int unauthorizedRequests = 0;
                int otherResponses = 0;

                for (int i = 0; i < results.size(); i++) {
                    int responseStatus = results.get(i).get(30, TimeUnit.SECONDS);
                    System.out.println(
                            "Concurrent request " + (i + 1) + " returned HTTP " + responseStatus
                    );

                    if (responseStatus == 200) {
                        successfulRequests++;
                    } else if (responseStatus == 401) {
                        unauthorizedRequests++;
                    } else {
                        otherResponses++;
                    }
                }

                System.out.println("========== CONCURRENT TEST SUMMARY ==========");
                System.out.println("Total requests: " + workers);
                System.out.println("Successful (200): " + successfulRequests);
                System.out.println("Unauthorized (401): " + unauthorizedRequests);
                System.out.println("Other responses: " + otherResponses);
                System.out.println("=============================================");

                assertEquals(
                        workers,
                        successfulRequests,
                        "All concurrent requests with the same valid session must return 200"
                );

                // Verify the session remains valid after concurrent requests.
                browser.perform(
                                get("/api/auth/me")
                                        .cookie(new Cookie("SESSION", concurrentSessionId))
                        )
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.email").value(email));

                System.out.println("Final session verification: PASSED");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            users.deleteById(savedUser.getId());
            System.out.println("Temporary test user deleted.");
        }
    }

    private static String sessionIdFromSetCookie(String setCookie) {
        assertNotNull(setCookie, "Missing session cookie header");
        assertTrue(setCookie.startsWith("SESSION="), "Expected SESSION cookie");

        int end = setCookie.indexOf(';');
        String value = setCookie.substring("SESSION=".length(),
                end >= 0 ? end : setCookie.length());
        assertFalse(value.isBlank(), "Session cookie must not be empty");
        return value;
    }
}
