package com.api.inventory;

import com.api.inventory.dto.OrderRequestDTO;
import com.api.inventory.entity.*;
import com.api.inventory.repository.*;
import com.api.inventory.security.Permissions;
import com.api.inventory.service.BankPaymentService;
import com.api.inventory.service.OnlinePaymentService;
import com.api.inventory.service.OrderService;
import com.api.inventory.service.payments.BankGatewayClient;
import com.api.inventory.service.payments.TestBankGatewayClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "app.payments.bank.mode=test",
        "app.payments.sandbox.enabled=false"
})
@ActiveProfiles("mysql-integration")
@EnabledIfEnvironmentVariable(
        named = "INVENTORY_TEST_DB_PASSWORD",
        matches = ".+"
)
class MysqlBankPaymentConcurrencyTest {

    // =====================================================
    // DEPENDENCIES
    // =====================================================

    @Autowired
    BankPaymentService bank;

    @Autowired
    OnlinePaymentService onlinePayments;

    @Autowired
    OrderService orders;

    @Autowired
    UserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ItemMasterRepository items;

    @Autowired
    InventoryStockRepository stocks;

    @Autowired
    BankPaymentRepository bankPayments;

    @Autowired
    PaymentIntentRepository intents;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    // Spy on the simulated bank.
    // No actual bank or RMA gateway will be contacted.
    @MockitoSpyBean
    TestBankGatewayClient gateway;

    // =====================================================
    // DATABASE SAFETY CHECK
    // =====================================================

    private void requireLocalTestDatabase() throws Exception {

        try (Connection connection = dataSource.getConnection()) {

            String catalog = connection.getCatalog();
            String url = connection.getMetaData().getURL();

            assertEquals(
                    "inventory_session_test",
                    catalog,
                    "SAFETY CHECK FAILED: Wrong database. " +
                            "Refusing to run the payment concurrency test."
            );

            assertNotNull(
                    url,
                    "SAFETY CHECK FAILED: JDBC URL is missing."
            );

            boolean localMySQL =
                    url.startsWith("jdbc:mysql://localhost:")
                            || url.startsWith("jdbc:mysql://127.0.0.1:")
                            || url.startsWith("jdbc:mysql://localhost/")
                            || url.startsWith("jdbc:mysql://127.0.0.1/");

            assertTrue(
                    localMySQL,
                    "SAFETY CHECK FAILED: Database must be local MySQL. " +
                            "Actual URL: " + url
            );

            System.out.println(
                    "DATABASE SAFETY CHECK PASSED: " + catalog
            );
        }
    }

    // =====================================================
    // MAIN CONCURRENCY TEST
    // =====================================================

    @Test
    void simultaneousPayRequestsMustNotDebitTwice()
            throws Exception {

        // IMPORTANT:
        // Check database BEFORE creating any test records.
        requireLocalTestDatabase();

        String unique = UUID.randomUUID()
                .toString()
                .substring(0, 8);

        User customer = null;
        Long itemId = null;
        Long orderId = null;
        String reference = null;
        boolean workersStopped = true;
        try {
            customer = createCustomer(unique);
            itemId = createItem(unique);

            authenticate(customer);

            Order order = orders.createOrder(
                    orderRequest(customer, itemId)
            );

            orderId = order.getOrderId();
            reference = onlinePayments.start(
                    order.getOrderId(),
                    "BANK"
            ).reference();

            // Request simulated verification code.
            assertEquals(
                    BankPayment.CODE_SENT,
                    bank.requestCode(
                            reference,
                            "1010",
                            "110022334321"
                    ).status()
            );

            AtomicInteger debitCalls = new AtomicInteger();

            CountDownLatch debitEntered =
                    new CountDownLatch(1);

            CountDownLatch releaseDebit =
                    new CountDownLatch(1);

            // =================================================
            // PAUSE THE SIMULATED BANK DEBIT
            // =================================================

            doAnswer(invocation -> {

                debitCalls.incrementAndGet();

                // Tell the test the first debit was reached.
                debitEntered.countDown();

                // Pause here while the test checks MySQL.
                if (!releaseDebit.await(
                        20,
                        TimeUnit.SECONDS
                )) {
                    throw new IllegalStateException(
                            "Timed out waiting to release simulated debit"
                    );
                }

                // Simulate the gateway giving no definite answer.
                return BankGatewayClient.Answer.no(
                        "NO_ANSWER",
                        "Simulated uncertain bank response"
                );

            }).when(gateway).debit(
                    anyString(),
                    anyString()
            );

            final String paymentReference = reference;
            final User paymentCustomer = customer;

            ExecutorService executor =
                    Executors.newFixedThreadPool(2);
            workersStopped = false;

            try {

                // Each thread must authenticate independently.
                Callable<String> task = () -> {

                    authenticate(paymentCustomer);

                    try {
                        return bank.pay(
                                paymentReference,
                                "123456"
                        ).status();

                    } catch (IllegalStateException expected) {

                        return "BLOCKED";

                    } finally {

                        SecurityContextHolder.clearContext();
                    }
                };

                // =============================================
                // REQUEST A
                // =============================================

                Future<String> first =
                        executor.submit(task);

                assertTrue(
                        debitEntered.await(
                                15,
                                TimeUnit.SECONDS
                        ),
                        "First payment never reached the simulated gateway"
                );

                // =============================================
                // CHECK COMMITTED MYSQL STATE
                // =============================================

                // Gateway is paused right now.
                // DEBIT_PENDING must already be committed.

                BankPayment pending = bankPayments
                        .findByIntentReference(reference)
                        .orElseThrow();

                assertEquals(
                        BankPayment.DEBIT_PENDING,
                        pending.getStatus(),
                        "DEBIT_PENDING must be committed before debit()"
                );

                assertNotNull(
                        pending.getDebitRequestedAt(),
                        "Debit reservation must have a timestamp"
                );

                System.out.println(
                        "PASSED: DEBIT_PENDING committed before gateway debit"
                );

                // =============================================
                // REQUEST B
                // =============================================

                Future<String> second =
                        executor.submit(task);

                assertEquals(
                        "BLOCKED",
                        second.get(
                                15,
                                TimeUnit.SECONDS
                        ),
                        "Second payment request must be blocked"
                );

                assertEquals(
                        1,
                        debitCalls.get(),
                        "Only one bank debit invocation is permitted"
                );

                System.out.println(
                        "PASSED: Second concurrent payment was blocked"
                );

                // =============================================
                // RELEASE THE FIRST GATEWAY CALL
                // =============================================

                releaseDebit.countDown();

                assertEquals(
                        BankPayment.CHECK_BANK,
                        first.get(
                                20,
                                TimeUnit.SECONDS
                        )
                );

                assertEquals(
                        1,
                        debitCalls.get(),
                        "Gateway must be called exactly once"
                );

                // =============================================
                // VERIFY FINAL DATABASE STATUS
                // =============================================

                BankPayment saved = bankPayments
                        .findByIntentReference(reference)
                        .orElseThrow();

                assertEquals(
                        BankPayment.CHECK_BANK,
                        saved.getStatus()
                );

                assertNotNull(
                        saved.getDebitRequestedAt()
                );

                assertEquals(
                        PaymentIntent.CREATED,
                        intents.findByReference(reference)
                                .orElseThrow()
                                .getStatus(),
                        "Uncertain payment intent must remain open"
                );

                System.out.println(
                        "PASSED: Exactly one simulated debit invocation"
                );

                System.out.println(
                        "PASSED: Final bank payment status CHECK_BANK"
                );

                System.out.println(
                        "MYSQL BANK PAYMENT CONCURRENCY TEST PASSED"
                );

            } finally {
                releaseDebit.countDown();

                executor.shutdown();

                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();

                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Payment workers are still running. " +
                                        "Database cleanup must not proceed."
                        );
                    }
                }

                if (executor.isTerminated()) workersStopped = true;
                SecurityContextHolder.clearContext();
            }
        } finally {
            SecurityContextHolder.clearContext();
            if (workersStopped) {
                cleanupTestRecords(unique, customer, itemId, orderId, reference);
            } else {
                System.err.println("CLEANUP SKIPPED: payment workers may still be active");
            }
        }
    }


    /**
     * Two requests for the same order must reuse the same payment intent.
     * Uses a real local MySQL row lock and cleans up even if the assertion fails.
     */
    @Test
    void simultaneousPaymentStartsReuseOneIntent() throws Exception {
        requireLocalTestDatabase();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        User customer = null;
        Long itemId = null;
        Long orderId = null;
        String reference = null;
        ExecutorService executor = null;
        CountDownLatch releaseStart = new CountDownLatch(1);
        boolean workersStopped = true;

        try {
            customer = createCustomer(unique);
            itemId = createItem(unique);
            authenticate(customer);
            orderId = orders.createOrder(orderRequest(customer, itemId)).getOrderId();

            final User paymentCustomer = customer;
            final Long paymentOrderId = orderId;
            CountDownLatch ready = new CountDownLatch(2);
            executor = Executors.newFixedThreadPool(2);
            workersStopped = false;

            Callable<String> startPayment = () -> {
                authenticate(paymentCustomer);
                try {
                    ready.countDown();
                    if (!releaseStart.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out awaiting concurrent payment start");
                    }
                    return onlinePayments.start(paymentOrderId, "BANK").reference();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };

            Future<String> first = executor.submit(startPayment);
            Future<String> second = executor.submit(startPayment);
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Both payment threads must be ready");
            releaseStart.countDown();

            String firstReference = first.get(30, TimeUnit.SECONDS);
            String secondReference = second.get(30, TimeUnit.SECONDS);
            assertNotNull(firstReference);
            reference = firstReference;

            assertEquals(firstReference, secondReference,
                    "Two concurrent payment starts must reuse the same reference");
            List<PaymentIntent> savedIntents = intents.findByOrderIdOrderByIdDesc(orderId);
            assertEquals(1, savedIntents.size(),
                    "There must be exactly one payment intent for this test order");
            assertEquals(firstReference, savedIntents.get(0).getReference());
            assertEquals(PaymentIntent.CREATED, savedIntents.get(0).getStatus());
            System.out.println("PASSED: Concurrent starts reused one payment intent");
        } finally {
            releaseStart.countDown();
            if (executor != null) {
                executor.shutdown();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        workersStopped = false;
                    }
                }
                if (executor.isTerminated()) {
                    workersStopped = true;
                }
            }
            SecurityContextHolder.clearContext();
            if (!workersStopped) {
                throw new IllegalStateException(
                        "Payment start workers are still active; refusing database cleanup");
            }
            // Discover any extra intent if the concurrency assertion fails, and remove
            // only records tied to the uniquely identified test order.
            cleanupTestRecords(unique, customer, itemId, orderId, reference);
        }
    }

    // Only removes records created by this invocation. All deletes are atomic.
    private void cleanupTestRecords(String unique, User customer, Long itemId,
                                    Long orderId, String reference) throws Exception {
        requireLocalTestDatabase();
        final String expectedEmail = "concurrency-" + unique + "@test.local";
        final String expectedSku = "CON-" + unique;
        if (customer == null && itemId == null && orderId == null && reference == null) return;

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            // Fail closed if any identity no longer matches the unique test fixture.
            if (customer != null) {
                assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE id = ? AND email = ?",
                        Long.class, customer.getId(), expectedEmail));
            }
            if (itemId != null) {
                assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM item_master WHERE item_id = ? AND sku = ?",
                        Long.class, itemId, expectedSku));
            }
            if (orderId != null) {
                assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM orders WHERE order_id = ? AND customer_email = ?",
                        Long.class, orderId, expectedEmail));
            }
            if (reference != null) {
                assertNotNull(orderId, "Payment reference exists without tracked order");
                assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_intents WHERE reference = ? AND order_id = ? AND customer_email = ?",
                        Long.class, reference, orderId, expectedEmail));
            }
            if (orderId != null && itemId != null) {
                assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_items WHERE order_id = ? AND item_id = ?",
                        Long.class, orderId, itemId));
            }
            if (orderId != null) {
                // Never silently remove unexpected downstream business records.
                for (String table : List.of("shipments", "coupon_redemptions",
                        "earnings_ledger", "return_requests", "sales_returns", "order_feedback")) {
                    assertEquals(0L, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE order_id = ?",
                            Long.class, orderId), "Unexpected related records in " + table);
                }
                assertEquals(0L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payments WHERE order_id = ?",
                        Long.class, orderId), "Unexpected settled payment record");
            }
            if (itemId != null) {
                for (String table : List.of("transactions", "product_reviews", "wishlist_items",
                        "return_request_items", "sales_return_items")) {
                    assertEquals(0L, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE item_id = ?",
                            Long.class, itemId), "Unexpected related records in " + table);
                }
            }

            // If a broken concurrent start produced multiple intents, still clean up
            // all (and only) attempts owned by this unique test order/customer.
            List<String> testReferences = List.of();
            if (orderId != null) {
                assertEquals(0L, jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment_intents WHERE order_id = ? AND customer_email <> ?",
                                Long.class, orderId, expectedEmail),
                        "Unexpected payment intent owner: refusing cleanup");
                testReferences = jdbc.queryForList(
                        "SELECT reference FROM payment_intents WHERE order_id = ? AND customer_email = ?",
                        String.class, orderId, expectedEmail);
                for (String paymentReference : testReferences) {
                    assertEquals(0L, jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM bank_payments WHERE intent_reference = ? AND order_id <> ?",
                                    Long.class, paymentReference, orderId),
                            "Unexpected bank-payment order: refusing cleanup");
                }
                assertEquals(0L, jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bank_payments WHERE order_id = ? " +
                                        "AND intent_reference NOT IN " +
                                        "(SELECT reference FROM payment_intents WHERE order_id = ? AND customer_email = ?)",
                                Long.class, orderId, orderId, expectedEmail),
                        "Unexpected bank-payment reference: refusing cleanup");
            }
            for (String paymentReference : testReferences) {
                jdbc.update("DELETE FROM payment_events WHERE intent_reference = ?", paymentReference);
                jdbc.update("DELETE FROM bank_payments WHERE intent_reference = ?", paymentReference);
                jdbc.update("DELETE FROM payment_intents WHERE reference = ?", paymentReference);
            }
            if (orderId != null) {
                jdbc.update("DELETE FROM order_item_batches WHERE order_item_id IN " +
                        "(SELECT order_item_id FROM order_items WHERE order_id = ?)", orderId);
                jdbc.update("DELETE FROM order_items WHERE order_id = ?", orderId);
                jdbc.update("DELETE FROM order_packages WHERE order_id = ?", orderId);
                jdbc.update("DELETE FROM tax_details WHERE order_id = ?", orderId);
                jdbc.update("DELETE FROM orders WHERE order_id = ? AND customer_email = ?",
                        orderId, expectedEmail);
            }
            if (itemId != null) {
                jdbc.update("DELETE FROM stock_alerts WHERE item_id = ?", itemId);
                jdbc.update("DELETE FROM stock_batches WHERE item_id = ?", itemId);
                jdbc.update("DELETE FROM inventory_stock WHERE item_id = ?", itemId);
                jdbc.update("DELETE FROM item_master WHERE item_id = ? AND sku = ?", itemId, expectedSku);
            }
            if (customer != null) {
                jdbc.update("DELETE FROM customers WHERE user_id = ?", customer.getId());
                jdbc.update("DELETE FROM users WHERE id = ? AND email = ?", customer.getId(), expectedEmail);
            }
        });
        System.out.println("CLEANUP PASSED: removed only records created by this test run");
    }

    // =====================================================
    // CREATE TEST CUSTOMER
    // =====================================================

    private User createCustomer(String unique) {

        User user = new User();

        user.setName(
                "Concurrency Test " + unique
        );

        user.setEmail(
                "concurrency-" + unique + "@test.local"
        );

        user.setPhone(
                "17" + (
                        100000 +
                                Math.abs(
                                        unique.hashCode() % 900000
                                )
                )
        );

        user.setPassword(
                encoder.encode(
                        UUID.randomUUID().toString()
                )
        );

        user.setActive(true);

        user.setRole(
                roles.findByName("USER")
                        .orElseThrow()
        );

        return users.save(user);
    }

    // =====================================================
    // CREATE TEST PRODUCT
    // =====================================================

    private Long createItem(String unique) {

        ItemMaster item = new ItemMaster();

        item.setItemName(
                "Concurrency Item " + unique
        );

        item.setSku(
                "CON-" + unique
        );

        item.setSellingPrice(
                new BigDecimal("100.00")
        );

        item.setMrp(
                new BigDecimal("100.00")
        );

        item.setIsActive(true);

        item.setCreatedAt(
                LocalDateTime.now()
        );

        Long itemId = items.save(item)
                .getItemId();

        InventoryStock stock =
                new InventoryStock();

        stock.setItemId(itemId);

        stock.setCurrentQuantity(5);

        stocks.save(stock);

        return itemId;
    }

    // =====================================================
    // BUILD ORDER REQUEST
    // =====================================================

    private OrderRequestDTO orderRequest(
            User user,
            Long itemId
    ) {

        OrderRequestDTO request =
                new OrderRequestDTO();

        request.setCustomerName(
                user.getName()
        );

        request.setCustomerEmail(
                user.getEmail()
        );

        request.setCustomerPhone(
                user.getPhone()
        );

        request.setAddress(
                "Concurrency test address"
        );

        request.setItems(
                List.of(
                        new OrderRequestDTO.Item(
                                itemId,
                                1,
                                null
                        )
                )
        );

        return request;
    }

    // =====================================================
    // AUTHENTICATE TEST CUSTOMER
    // =====================================================

    private void authenticate(User user) {

        String role =
                user.getRole().getName();

        List<SimpleGrantedAuthority> authorities =
                new ArrayList<>();

        authorities.add(
                new SimpleGrantedAuthority(
                        "ROLE_" + role
                )
        );

        Permissions.defaultsFor(role)
                .forEach(permission ->
                        authorities.add(
                                new SimpleGrantedAuthority(
                                        permission
                                )
                        )
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken
                                .authenticated(
                                        user.getEmail(),
                                        null,
                                        authorities
                                )
                );
    }
}
