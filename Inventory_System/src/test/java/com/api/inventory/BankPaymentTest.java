package com.api.inventory;

import com.api.inventory.controller.BankPaymentController;
import com.api.inventory.dto.OrderRequestDTO;
import com.api.inventory.entity.*;
import com.api.inventory.repository.*;
import com.api.inventory.security.Permissions;
import com.api.inventory.service.BankPaymentService;
import com.api.inventory.service.BankPaymentService.BankView;
import com.api.inventory.service.EmailService;
import com.api.inventory.service.OnlinePaymentService;
import com.api.inventory.service.OrderService;
import com.api.inventory.service.ReceiptService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Paying from a bank account with a code sent to the phone, in TEST MODE (the code is 123456, an account ending
 * 0000 is unknown, one ending 9999 has no money). Runs on an empty in-memory database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bankpay;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.session.jdbc.initialize-schema=always",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "app.payments.bank.mode=test",
        "app.payments.sandbox.enabled=false",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class BankPaymentTest {

    private static final String ACCOUNT = "1100 2233 4321";

    @MockitoBean EmailService email;

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    @Autowired ItemMasterRepository items;
    @Autowired InventoryStockRepository stock;
    @Autowired OrderRepository orderRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired PaymentIntentRepository intentRepo;
    @Autowired BankPaymentRepository bankPaymentRepo;
    @Autowired PaymentEventRepository eventRepo;
    @Autowired OrderService orders;
    @Autowired OnlinePaymentService onlinePayments;
    @Autowired BankPaymentService bank;
    @Autowired ReceiptService receipts;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theRightCodePaysAndConfirmsTheOrder() {
        User customer = user("karma@bank.bt", "USER");
        Long soap = houseItem("Herbal soap", "150.00", 5);
        as(customer);
        Order order = orders.createOrder(orderFor(customer, soap, 2));

        assertTrue(onlinePayments.options().stream().anyMatch(o -> o.code().equals("BANK") && o.label().contains("TEST MODE")));
        OnlinePaymentService.StartResult started = onlinePayments.start(order.getOrderId(), "BANK");
        assertTrue(started.redirectUrl().endsWith("/pay/bank?ref=" + started.reference()));
        String ref = started.reference();

        BankView fresh = bank.view(ref);
        assertEquals("STARTED", fresh.status());
        assertTrue(fresh.testMode());
        assertEquals(0, order.getTotalAmount().compareTo(fresh.amount()), "the amount comes from the order (with delivery)");
        assertEquals(6, bank.banks().size());

        BankView sent = bank.requestCode(ref, "1020", ACCOUNT);
        assertEquals("CODE_SENT", sent.status());
        assertEquals("4321", sent.accountLast4());
        assertEquals("Bhutan National Bank", sent.bankName());
        assertNotNull(sent.codeExpiresAt());

        BankView paid = bank.pay(ref, "123456");
        assertEquals("PAID", paid.status());

        Order after = orderRepo.findById(order.getOrderId()).orElseThrow();
        assertEquals("PAID", after.getPaymentStatus());
        assertEquals("CONFIRMED", after.getOrderStatus());
        assertEquals(3, stock.findByItemId(soap).orElseThrow().getCurrentQuantity(), "stock taken once");
        Payment payment = paymentRepo.findByOrderId(order.getOrderId()).orElseThrow();
        assertEquals("online", payment.getPaymentMethod());
        assertEquals("confirmed", payment.getStatus());
        BankPayment row = bankPaymentRepo.findByIntentReference(ref).orElseThrow();
        assertTrue(row.getBankReference().startsWith("TJ"), "the bank's journal number is kept: " + row.getBankReference());
        assertEquals("ONLINE BANK " + row.getBankReference(), payment.getJournalNumber(), "and the payment is recorded under it");
        assertEquals(row.getBankReference(), paid.bankReference());
        assertEquals("PAID", intentRepo.findByReference(ref).orElseThrow().getStatus());

        ReceiptService.Receipt receipt = receipts.receipt(order.getOrderId());
        assertEquals("Bank account (RMA Payment Gateway)", receipt.payment().method());
        assertEquals(row.getBankReference(), receipt.payment().journal());
        assertEquals("Bhutan National Bank, account ending 4321", receipt.payment().account());

        assertEquals("PAID", row.getStatus());
        assertTrue(row.isTestMode());
        assertEquals(1, row.getCodesSent());
        List<String> steps = eventRepo.findByIntentReferenceOrderByAtAsc(ref).stream().map(PaymentEvent::getStep).toList();
        assertEquals(List.of("STARTED",
                "CODE_REQUESTED",
                "CODE_SENT",
                "DEBIT_REQUESTED",
                "PAID"), steps);

        // paying again changes nothing
        assertThrows(IllegalStateException.class, () -> bank.pay(ref, "123456"));
        assertEquals(3, stock.findByItemId(soap).orElseThrow().getCurrentQuantity());
    }

    @Test
    void onlyTheLastFourDigitsAreKept() {
        User customer = user("sonam@bank.bt", "USER");
        as(customer);
        String ref = startFor(customer);
        bank.requestCode(ref, "1010", ACCOUNT);
        bank.pay(ref, "654321"); // a wrong code, so the code is not stored either

        BankPayment row = bankPaymentRepo.findByIntentReference(ref).orElseThrow();
        String everything = row.getAccountLast4() + row.getBankCode() + row.getBankName() + row.getGatewayTransactionId()
                + row.getFailureReason() + eventRepo.findByIntentReferenceOrderByAtAsc(ref).stream()
                .map(e -> e.getStep() + e.getResult() + e.getMessage()).toList();
        assertFalse(everything.contains("11002233"), "the account number is not stored");
        assertFalse(everything.contains("654321"), "the code is not stored");
        assertEquals("4321", row.getAccountLast4());

        String logged = new BankPaymentController.CodeRequest("1010", "110022334321").toString()
                + new BankPaymentController.PayRequest("123456");
        assertFalse(logged.contains("110022334321") || logged.contains("123456"), "request bodies print hidden: " + logged);
    }

    @Test
    void threeWrongCodesStopThePaymentAndNothingIsTaken() {
        User customer = user("pema@bank.bt", "USER");
        Long oil = houseItem("Hair oil", "90.00", 3);
        as(customer);
        Order order = orders.createOrder(orderFor(customer, oil, 1));
        String ref = onlinePayments.start(order.getOrderId(), "BANK").reference();
        bank.requestCode(ref, "1010", ACCOUNT);

        BankView first = bank.pay(ref, "111111");
        assertEquals("CODE_SENT", first.status());
        assertEquals(2, first.wrongCodesLeft());
        assertTrue(first.message().contains("2 tries left"), first.message());
        assertTrue(bank.pay(ref, "222222").message().contains("1 try left"));
        BankView stopped = bank.pay(ref, "333333");
        assertEquals("FAILED", stopped.status());
        assertTrue(stopped.message().contains("wrong 3 times"), stopped.message());

        assertEquals("FAILED", intentRepo.findByReference(ref).orElseThrow().getStatus());
        Order after = orderRepo.findById(order.getOrderId()).orElseThrow();
        assertEquals("PENDING", after.getPaymentStatus());
        assertEquals(3, stock.findByItemId(oil).orElseThrow().getCurrentQuantity(), "nothing taken");
        assertThrows(IllegalStateException.class, () -> bank.pay(ref, "123456"), "the stopped payment cannot be finished");

        String again = onlinePayments.start(order.getOrderId(), "BANK").reference();
        assertNotEquals(ref, again, "a new payment can be started");
        assertEquals("STARTED", bank.view(again).status());
    }

    @Test
    void unknownAccountsAndRefusalsAreExplained() {
        User customer = user("tshering@bank.bt", "USER");
        as(customer);
        String ref = startFor(customer);

        BankView unknown = bank.requestCode(ref, "1030", "5566770000");
        assertEquals("STARTED", unknown.status(), "no code was sent");
        assertTrue(unknown.message().contains("does not know this account"), unknown.message());

        assertEquals("CODE_SENT", bank.requestCode(ref, "1030", "5566779999").status());
        BankView refused = bank.pay(ref, "123456");
        assertEquals("FAILED", refused.status());
        assertTrue(refused.message().contains("not enough money"), refused.message());
        assertEquals("FAILED", intentRepo.findByReference(ref).orElseThrow().getStatus());
    }

    @Test
    void aFourthCodeEndsTheAttempt() {
        User customer = user("yeshi@bank.bt", "USER");
        as(customer);
        String ref = startFor(customer);
        bank.requestCode(ref, "1010", ACCOUNT);
        bank.requestCode(ref, "1010", ACCOUNT);
        assertEquals(0, bank.requestCode(ref, "1010", ACCOUNT).codesLeft());
        BankView ended = bank.requestCode(ref, "1010", ACCOUNT);
        assertEquals("FAILED", ended.status());
        assertEquals("FAILED", intentRepo.findByReference(ref).orElseThrow().getStatus());
    }

    @Test
    void whenTheBankNeverAnswersStaffCheckAndSettleIt() {
        User admin = user("boss@bank.bt", "ADMIN");
        User customer = user("sangay@bank.bt", "USER");
        Long honey = houseItem("Wild honey", "300.00", 4);
        as(customer);
        Order order = orders.createOrder(orderFor(customer, honey, 1));
        String ref = onlinePayments.start(order.getOrderId(), "BANK").reference();
        bank.requestCode(ref, "1010", "4455665555"); // test mode: the bank never answers the debit

        BankView waiting = bank.pay(ref, "123456");
        assertEquals("CHECK_BANK", waiting.status());
        assertTrue(waiting.message().contains("do not pay again"), waiting.message());
        assertEquals("PENDING", orderRepo.findById(order.getOrderId()).orElseThrow().getPaymentStatus(), "not confirmed on a guess");
        assertThrows(IllegalStateException.class, () -> bank.pay(ref, "123456"), "no second debit");
        assertThrows(IllegalStateException.class, () -> onlinePayments.start(order.getOrderId(), "BANK"), "no second payment meanwhile");
        assertThrows(IllegalStateException.class, () -> onlinePayments.cancel(ref), "and no cancelling it");
        assertThrows(AccessDeniedException.class, () -> bank.toCheck(), "customers cannot settle");

        as(admin);
        assertEquals(List.of(ref), bank.toCheck().stream().map(BankPaymentService.ToCheck::reference).toList());
        assertThrows(IllegalArgumentException.class, () -> bank.settle(ref, true, " "), "paid needs the bank's journal number");
        assertEquals("PAID", bank.settle(ref, true, "bob 99887766").status());
        assertTrue(bank.toCheck().isEmpty());
        Order after = orderRepo.findById(order.getOrderId()).orElseThrow();
        assertEquals("PAID", after.getPaymentStatus());
        assertEquals("CONFIRMED", after.getOrderStatus());
        assertEquals("ONLINE BANK BOB 99887766", paymentRepo.findByOrderId(order.getOrderId()).orElseThrow().getJournalNumber());
        assertThrows(IllegalStateException.class, () -> bank.settle(ref, false, null), "settled once");

        // the other way: the bank says nothing was taken, and the customer can pay again
        as(customer);
        Order second = orders.createOrder(orderFor(customer, honey, 1));
        String ref2 = onlinePayments.start(second.getOrderId(), "BANK").reference();
        bank.requestCode(ref2, "1010", "4455665555");
        bank.pay(ref2, "123456");
        as(admin);
        assertEquals("FAILED", bank.settle(ref2, false, null).status());
        as(customer);
        assertNotEquals(ref2, onlinePayments.start(second.getOrderId(), "BANK").reference());
        assertEquals("PENDING", orderRepo.findById(second.getOrderId()).orElseThrow().getPaymentStatus());
    }

    @Test
    void guessingAccountNumbersIsLimited() {
        User customer = user("jigme@bank.bt", "USER");
        as(customer);
        String ref = startFor(customer);
        for (int i = 0; i < 10; i++) {
            assertEquals("STARTED", bank.requestCode(ref, "1010", "77880" + i + "0000").status());
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bank.requestCode(ref, "1010", ACCOUNT));
        assertTrue(e.getMessage().contains("Too many"), e.getMessage());
    }

    @Test
    void cancellingClosesTheBankPayment() {
        User customer = user("ugyen@bank.bt", "USER");
        as(customer);
        String ref = startFor(customer);
        bank.requestCode(ref, "1010", ACCOUNT);
        onlinePayments.cancel(ref);
        assertEquals("CANCELLED", bankPaymentRepo.findByIntentReference(ref).orElseThrow().getStatus());
        assertEquals("CANCELLED", bank.view(ref).status());
        assertThrows(IllegalStateException.class, () -> bank.pay(ref, "123456"));
    }

    @Test
    void wrongInputAndOtherPeopleAreRefused() {
        User customer = user("dorji@bank.bt", "USER");
        User stranger = user("stranger@bank.bt", "USER");
        as(customer);
        String ref = startFor(customer);
        assertThrows(IllegalArgumentException.class, () -> bank.requestCode(ref, "1010", "12ab"));
        assertThrows(IllegalArgumentException.class, () -> bank.requestCode(ref, "NOPE", ACCOUNT));
        assertThrows(IllegalStateException.class, () -> bank.pay(ref, "123456"), "no code asked for yet");

        as(stranger);
        assertThrows(AccessDeniedException.class, () -> bank.view(ref));
        assertThrows(AccessDeniedException.class, () -> bank.requestCode(ref, "1010", ACCOUNT));
        assertThrows(AccessDeniedException.class, () -> bank.pay(ref, "123456"));
    }

    @Test
    void theWebsiteCallsWork() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
        User customer = user("kinley@bank.bt", "USER");
        as(customer);
        String ref = startFor(customer);
        SecurityContextHolder.clearContext();

        http.perform(get("/api/online-payments/bank/" + ref)).andExpect(status().isUnauthorized());

        MockHttpSession session = (MockHttpSession) http.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"kinley@bank.bt\",\"password\":\"secret-1\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);

        http.perform(get("/api/online-payments/bank/banks").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("1010"));
        http.perform(post("/api/online-payments/bank/" + ref + "/code").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"1040\",\"accountNumber\":\"200300404321\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CODE_SENT"))
                .andExpect(jsonPath("$.accountLast4").value("4321")).andExpect(jsonPath("$.testCode").value("123456"));
        http.perform(post("/api/online-payments/bank/" + ref + "/pay").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"12\"}"))
                .andExpect(status().is4xxClientError());
        http.perform(post("/api/online-payments/bank/" + ref + "/pay").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
    }

    /**
     * Simulates a restart after DEBIT_PENDING was durably committed, but before a
     * gateway result was saved. A second Pay, new code, cancel, replacement, and
     * scheduled expiry must all leave the unresolved debit alone.
     */
    @Test
    void aPendingDebitCannotBeRetriedCancelledReplacedOrExpired() {
        User customer = user("pending@bank.bt", "USER");
        as(customer);
        String ref = startFor(customer);
        bank.requestCode(ref, "1010", ACCOUNT);

        BankPayment row = bankPaymentRepo.findByIntentReference(ref).orElseThrow();
        row.setStatus(BankPayment.DEBIT_PENDING);
        row.setDebitRequestedAt(Instant.now().minus(Duration.ofHours(2)));
        bankPaymentRepo.saveAndFlush(row);

        PaymentIntent attempt = intentRepo.findByReference(ref).orElseThrow();
        Long orderId = attempt.getOrderId();
        attempt.setCreatedAt(Instant.now().minus(Duration.ofHours(2)));
        intentRepo.saveAndFlush(attempt);

        long debitEventsBefore = eventRepo.findByIntentReferenceOrderByAtAsc(ref).stream()
                .filter(e -> "DEBIT_REQUESTED".equals(e.getStep())).count();

        assertEquals(BankPayment.CHECK_BANK, bank.view(ref).status(),
                "the customer must be warned that a debit might have occurred");
        assertThrows(IllegalStateException.class, () -> bank.pay(ref, "123456"),
                "the app must not send the same debit again");
        assertThrows(IllegalStateException.class, () -> bank.requestCode(ref, "1010", ACCOUNT),
                "a new code must not reopen a debit in progress");
        assertThrows(IllegalStateException.class, () -> onlinePayments.cancel(ref),
                "an uncertain debit must not be cancelled");
        assertThrows(IllegalStateException.class, () -> onlinePayments.start(orderId, "BANK"),
                "an uncertain debit must not be replaced by another payment attempt");

        onlinePayments.expireOld();
        assertEquals(PaymentIntent.CREATED, intentRepo.findByReference(ref).orElseThrow().getStatus(),
                "the scheduled expiry must not close an uncertain debit");
        assertEquals(BankPayment.DEBIT_PENDING,
                bankPaymentRepo.findByIntentReference(ref).orElseThrow().getStatus());
        assertEquals("PENDING", orderRepo.findById(orderId).orElseThrow().getPaymentStatus(),
                "payment must not be confirmed without a bank result");
        assertEquals(debitEventsBefore, eventRepo.findByIntentReferenceOrderByAtAsc(ref).stream()
                .filter(e -> "DEBIT_REQUESTED".equals(e.getStep())).count(),
                "no second debit request event should be recorded");

        // This test manually created DEBIT_PENDING and NEVER called the gateway.
        // Close the artificial pending attempt so it cannot affect other tests
        // sharing the same in-memory H2 database.
        as(user("pending-cleanup@bank.bt", "ADMIN"));
        assertEquals(BankPayment.FAILED, bank.settle(ref, false, null).status(),
                "cleanup: no simulated bank debit was ever sent");
        assertTrue(bank.toCheck().stream().noneMatch(p -> ref.equals(p.reference())),
                "cleanup must remove the artificial pending attempt from staff's queue");
    }

    /**
     * An abandoned DEBIT_PENDING debit appears for staff reconciliation, but
     * staff must wait for a recent in-flight call to finish. Simulates staff
     * verifying a successful bank debit by entering its journal number.
     */
    @Test
    void staffCanReconcileAnAbandonedDebitReservation() {
        User customer = user("unfinished@bank.bt", "USER");
        User admin = user("reconciler@bank.bt", "ADMIN");
        as(customer);
        String ref = startFor(customer);
        bank.requestCode(ref, "1010", ACCOUNT);

        BankPayment row = bankPaymentRepo.findByIntentReference(ref).orElseThrow();
        row.setStatus(BankPayment.DEBIT_PENDING);
        row.setDebitRequestedAt(Instant.now());
        bankPaymentRepo.saveAndFlush(row);

        assertThrows(AccessDeniedException.class, () -> bank.toCheck(),
                "only authorized staff can see pending debits");
        as(admin);
        assertTrue(bank.toCheck().stream().anyMatch(p -> ref.equals(p.reference())),
                "an orphaned debit must be visible to staff");
        assertThrows(IllegalStateException.class, () -> bank.settle(ref, true, "BOB 99887766"),
                "staff must not settle a newly in-flight bank request");

        BankPayment aged = bankPaymentRepo.findByIntentReference(ref).orElseThrow();
        aged.setDebitRequestedAt(Instant.now().minus(Duration.ofMinutes(10)));
        bankPaymentRepo.saveAndFlush(aged);

        BankView settled = bank.settle(ref, true, "bob 99887766");
        assertEquals(BankPayment.PAID, settled.status());
        assertEquals("BOB 99887766", bankPaymentRepo.findByIntentReference(ref).orElseThrow().getBankReference());
        assertEquals(PaymentIntent.PAID, intentRepo.findByReference(ref).orElseThrow().getStatus());
        assertEquals("PAID", orderRepo.findById(settled.orderId()).orElseThrow().getPaymentStatus());
        assertEquals("CONFIRMED", orderRepo.findById(settled.orderId()).orElseThrow().getOrderStatus());
        assertTrue(bank.toCheck().stream().noneMatch(p -> ref.equals(p.reference())));
        assertThrows(IllegalStateException.class, () -> bank.settle(ref, true, "BOB 99887766"),
                "settling an already reconciled debit a second time must be rejected");
    }

    // ================= Helpers =================

    private String startFor(User customer) {
        Long item = houseItem("Item " + System.nanoTime(), "120.00", 4);
        Order order = orders.createOrder(orderFor(customer, item, 1));
        return onlinePayments.start(order.getOrderId(), "BANK").reference();
    }

    private OrderRequestDTO orderFor(User customer, Long itemId, int quantity) {
        OrderRequestDTO request = new OrderRequestDTO();
        request.setCustomerName(customer.getName());
        request.setCustomerEmail(customer.getEmail());
        request.setCustomerPhone(customer.getPhone());
        request.setAddress("Motithang, Thimphu");
        request.setItems(List.of(new OrderRequestDTO.Item(itemId, quantity, null)));
        return request;
    }

    private User user(String email, String role) {
        User u = new User();
        u.setName(email.substring(0, email.indexOf('@')));
        u.setEmail(email);
        u.setPhone("17" + (100000 + Math.abs(email.hashCode() % 900000)));
        u.setPassword(encoder.encode("secret-1"));
        u.setActive(true);
        u.setRole(roles.findByName(role).orElseThrow());
        return users.save(u);
    }

    private void as(User u) {
        String role = u.getRole().getName();
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        Permissions.defaultsFor(role).forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(u.getEmail(), null, authorities));
    }

    private Long houseItem(String name, String price, int qty) {
        ItemMaster item = new ItemMaster();
        item.setItemName(name);
        item.setSku("B-" + System.nanoTime());
        item.setSellingPrice(new BigDecimal(price));
        item.setMrp(new BigDecimal(price));
        item.setIsActive(true);
        item.setCreatedAt(LocalDateTime.now());
        Long id = items.save(item).getItemId();
        InventoryStock s = new InventoryStock();
        s.setItemId(id);
        s.setCurrentQuantity(qty);
        stock.save(s);
        return id;
    }
}