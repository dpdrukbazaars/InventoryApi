package com.api.inventory;

import com.api.inventory.dto.OrderRequestDTO;
import com.api.inventory.entity.*;
import com.api.inventory.repository.*;
import com.api.inventory.security.Permissions;
import com.api.inventory.service.EmailService;
import com.api.inventory.service.NotificationService;
import com.api.inventory.service.OnlinePaymentService;
import com.api.inventory.service.OrderService;
import com.api.inventory.service.ProductPhotos;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The go-live features: "forgot password" by email, notifications along an order, paying online
 * (with the test gateway), and the product photo check. Runs on an empty in-memory database.
 * Emails are caught instead of sent, so the reset link can be read from them.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:golive;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "app.payments.sandbox.enabled=true",
        "app.payments.bank.mode=off",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class GoLiveFeaturesTest {

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
    @Autowired OrderPackageRepository packageRepo;
    @Autowired OrderService orders;
    @Autowired OnlinePaymentService onlinePayments;
    @Autowired NotificationService notifications;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ================= Forgot password =================

    @Test
    void forgotPasswordLinkWorksOnceAndSignsOutEverywhere() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
        User u = user("dawa@golive.bt", "USER", "17300001", "old-secret");
        when(email.isAvailable()).thenReturn(true); // the server can send emails

        MockHttpSession oldSession = (MockHttpSession) http.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dawa@golive.bt\",\"password\":\"old-secret\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);

        // an unknown email gets the same answer, and no email is sent
        http.perform(post("/api/auth/forgot-password").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@golive.bt\"}"))
                .andExpect(status().isOk());
        verify(email, never()).sendEmail(eq("nobody@golive.bt"), anyString(), anyString());

        http.perform(post("/api/auth/forgot-password").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"dawa@golive.bt\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(eq("dawa@golive.bt"), contains("new password"), body.capture());
        Matcher m = Pattern.compile("reset-password\\?token=([A-Za-z0-9_-]+)").matcher(body.getValue());
        assertTrue(m.find(), "the email holds the link");
        String token = m.group(1);

        http.perform(get("/api/auth/reset-password/check").param("token", token)).andExpect(jsonPath("$.valid").value(true));
        http.perform(get("/api/auth/reset-password/check").param("token", "made-up")).andExpect(jsonPath("$.valid").value(false));

        // too short: refused, the link still works
        http.perform(post("/api/auth/reset-password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"abc\"}")).andExpect(status().is4xxClientError());
        http.perform(post("/api/auth/reset-password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"new-secret-9\"}")).andExpect(status().isOk());

        // the link works once
        http.perform(post("/api/auth/reset-password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"another-one-1\"}")).andExpect(status().is4xxClientError());

        // the browser that was signed in before is signed out; only the new password works
        http.perform(get("/api/auth/me").session(oldSession)).andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dawa@golive.bt\",\"password\":\"old-secret\"}")).andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dawa@golive.bt\",\"password\":\"new-secret-9\"}")).andExpect(status().isOk());
        assertNotNull(users.findById(u.getId()).orElseThrow().getPasswordChangedAt());
    }

    // ================= Paying online, and the notifications along the way =================

    @Test
    void testPaymentConfirmsTheOrderOnceAndEveryoneIsTold() {
        User admin = user("owner@golive.bt", "ADMIN", "17300010", "x");
        User customer = user("pema@golive.bt", "USER", "17300011", "x");
        User stranger = user("other@golive.bt", "USER", "17300012", "x");
        Long soap = houseItem("Herbal soap", "150.00", 5);

        as(customer);
        Order order = orders.createOrder(orderFor(customer, soap, 2));
        assertTrue(kinds(customer).contains("ORDER_PLACED"));
        assertEquals(1, onlinePayments.options().size(), "the test gateway is offered");

        OnlinePaymentService.StartResult started = onlinePayments.start(order.getOrderId(), "SANDBOX");
        assertTrue(started.redirectUrl().contains("/pay/test?ref=" + started.reference()));
        assertEquals(started.reference(), onlinePayments.start(order.getOrderId(), "SANDBOX").reference(),
                "pressing Pay again reuses the same attempt");

        as(stranger);
        assertThrows(AccessDeniedException.class, () -> onlinePayments.status(started.reference()), "only the customer sees it");
        assertThrows(AccessDeniedException.class, () -> onlinePayments.start(order.getOrderId(), "SANDBOX"));

        as(customer);
        OnlinePaymentService.IntentView paid = onlinePayments.completeSandbox(started.reference(), "PAID");
        assertEquals("PAID", paid.status());

        Order after = orderRepo.findById(order.getOrderId()).orElseThrow();
        assertEquals("PAID", after.getPaymentStatus());
        assertEquals("CONFIRMED", after.getOrderStatus(), "confirmed without staff");
        assertEquals(3, stock.findByItemId(soap).orElseThrow().getCurrentQuantity(), "stock taken once");
        Payment payment = paymentRepo.findByOrderId(order.getOrderId()).orElseThrow();
        assertEquals("online", payment.getPaymentMethod());
        assertEquals("confirmed", payment.getStatus());
        assertEquals("TO_PACK", packageRepo.findByOrderIdOrderByIdAsc(order.getOrderId()).get(0).getStatus());

        // a second message about the same payment changes nothing
        onlinePayments.completeSandbox(started.reference(), "PAID");
        assertEquals(3, stock.findByItemId(soap).orElseThrow().getCurrentQuantity());
        assertThrows(IllegalStateException.class, () -> onlinePayments.start(order.getOrderId(), "SANDBOX"), "already paid");

        assertTrue(kinds(customer).contains("ORDER_PAID"), "the customer is told");
        assertTrue(kinds(admin).contains("NEW_ORDER"), "staff are told to pack our own products");

        // the bell: unread count, then all read
        assertTrue(notifications.unread(customer.getEmail()) >= 2);
        notifications.markAllRead(customer.getEmail());
        assertEquals(0, notifications.unread(customer.getEmail()));
    }

    @Test
    void aFailedTestPaymentLeavesTheOrderWaiting() {
        User customer = user("tashi@golive.bt", "USER", "17300021", "x");
        Long oil = houseItem("Hair oil", "90.00", 3);
        as(customer);
        Order order = orders.createOrder(orderFor(customer, oil, 1));

        String ref = onlinePayments.start(order.getOrderId(), "SANDBOX").reference();
        assertEquals("FAILED", onlinePayments.completeSandbox(ref, "FAILED").status());
        Order after = orderRepo.findById(order.getOrderId()).orElseThrow();
        assertEquals("PENDING", after.getPaymentStatus());
        assertEquals(3, stock.findByItemId(oil).orElseThrow().getCurrentQuantity(), "nothing taken");

        String again = onlinePayments.start(order.getOrderId(), "SANDBOX").reference();
        assertNotEquals(ref, again, "a new attempt can be made");
        assertThrows(IllegalStateException.class, () -> onlinePayments.start(order.getOrderId(), "NO_SUCH_GATEWAY"));
    }

    // ================= Product photos =================

    @Test
    void onlyRealPhotosAreAccepted() {
        assertThrows(IllegalStateException.class, () -> ProductPhotos.check(
                        new MockMultipartFile("image", "photo.jpg", "image/jpeg", "this is text, not a photo".getBytes())),
                "the name says .jpg, the content does not");
        assertDoesNotThrow(() -> ProductPhotos.check(new MockMultipartFile("image", "x.png", "image/png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0})));
        assertDoesNotThrow(() -> ProductPhotos.check(new MockMultipartFile("image", "x.webp", "image/webp",
                new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})));
    }

    // ================= Helpers =================

    private List<String> kinds(User u) {
        return notifications.latest(u.getEmail(), 50).stream().map(NotificationService.NotificationView::type).toList();
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

    private User user(String email, String role, String phone, String password) {
        User u = new User();
        u.setName(email.substring(0, email.indexOf('@')));
        u.setEmail(email);
        u.setPhone(phone);
        u.setPassword(encoder.encode(password));
        u.setActive(true);
        u.setRole(roles.findByName(role).orElseThrow());
        return users.save(u);
    }

    private void as(User u) {
        String role = u.getRole().getName();
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        Permissions.defaultsFor(role).forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(u.getEmail(), null, authorities));
    }

    private Long houseItem(String name, String price, int qty) {
        ItemMaster item = new ItemMaster();
        item.setItemName(name);
        item.setSku("G-" + System.nanoTime());
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