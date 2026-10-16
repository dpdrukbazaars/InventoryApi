package com.api.inventory;

import com.api.inventory.dto.OrderRequestDTO;
import com.api.inventory.dto.ReturnDTO;
import com.api.inventory.entity.*;
import com.api.inventory.repository.*;
import com.api.inventory.security.Permissions;
import com.api.inventory.service.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Deals and featured products, more photos, size/colour options, and coupon codes (the discount on the order, the
 * limits, a cancelled order freeing its use, and a return refunding only what was paid after the coupon).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shopoffers;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "app.files.store=database", // the test photos stay in the test database, not in the uploads folder
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class ShopOffersTest {

    @MockitoBean EmailService email;

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    @Autowired ItemMasterRepository items;
    @Autowired InventoryStockRepository stock;
    @Autowired OrderRepository orderRepo;
    @Autowired OrderService orders;
    @Autowired SalesReturnService returns;
    @Autowired ProductOptions options;
    @Autowired CouponRedemptionRepository redemptions;

    @AfterEach
    void nobody() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc http() {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
    }

    @Test
    void staffPutDealsAndFeaturedProductsOnTheHomePage() throws Exception {
        MockMvc http = http();
        MockHttpSession admin = signIn(http, person("admin@offers.bt", "17920001", "ADMIN"));
        MockHttpSession customer = signIn(http, person("buyer@offers.bt", "17920002", "USER"));
        Long tea = item("Suja tea", "120.00", 10, null).getItemId();

        http.perform(put("/api/items/" + tea + "/highlight").with(csrf()).session(customer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"highlight\":\"DEAL\"}")).andExpect(status().isForbidden());
        http.perform(put("/api/items/" + tea + "/highlight").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"highlight\":\"DEAL\",\"dealEndsAt\":\"2020-01-01T00:00:00Z\"}")).andExpect(status().isBadRequest());
        http.perform(put("/api/items/" + tea + "/highlight").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"highlight\":\"DEAL\",\"dealEndsAt\":\"" + Instant.now().plusSeconds(3600) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.highlight").value("DEAL"));
        http.perform(get("/api/items/allItems")).andExpect(jsonPath("$[?(@.itemId == " + tea + ")].highlight").value("DEAL"));

        // a deal that has ended is shown as nothing
        ItemMaster saved = items.findById(tea).orElseThrow();
        saved.setDealEndsAt(Instant.now().minusSeconds(60));
        items.save(saved);
        http.perform(get("/api/items/allItems")).andExpect(jsonPath("$[?(@.itemId == " + tea + ")].highlight").value(contains(nullValue())));
        http.perform(put("/api/items/" + tea + "/highlight").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"highlight\":\"FEATURED\"}")).andExpect(jsonPath("$.highlight").value("FEATURED")).andExpect(jsonPath("$.dealEndsAt").doesNotExist());
    }

    @Test
    void productsCanHaveMorePhotos() throws Exception {
        MockMvc http = http();
        MockHttpSession admin = signIn(http, person("photos@offers.bt", "17920003", "ADMIN"));
        Long kira = item("Kira", "2500.00", 3, null).getItemId();

        // the first photo of a product without one becomes its main photo
        http.perform(multipart("/api/items/" + kira + "/photos").with(csrf()).file(jpg()).session(admin)).andExpect(jsonPath("$", hasSize(0)));
        String main = items.findById(kira).orElseThrow().getImagePath();
        assertTrue(main.startsWith("/uploads/item-" + kira + "-"));
        for (int i = 0; i < 4; i++) {
            http.perform(multipart("/api/items/" + kira + "/photos").with(csrf()).file(jpg()).session(admin)).andExpect(status().isOk());
        }
        http.perform(multipart("/api/items/" + kira + "/photos").with(csrf()).file(jpg()).session(admin)).andExpect(status().isBadRequest()); // 5 in all
        http.perform(multipart("/api/items/" + kira + "/photos").with(csrf())
                .file(new MockMultipartFile("photo", "x.txt", "text/plain", "hello".getBytes())).session(admin)).andExpect(status().isBadRequest());

        String list = http.perform(get("/api/items/" + kira)).andExpect(jsonPath("$.photos", hasSize(4))).andReturn().getResponse().getContentAsString();
        Long first = Long.valueOf(list.replaceAll("(?s).*\"photos\":\\[\\{\"id\":(\\d+).*", "$1"));
        String firstPath = list.replaceAll("(?s).*\"photos\":\\[\\{\"id\":\\d+,\"path\":\"([^\"]+)\".*", "$1");
        http.perform(post("/api/items/" + kira + "/photos/" + first + "/main").with(csrf()).session(admin))
                .andExpect(jsonPath("$[0].path").value(main)); // the old main photo takes its place
        assertEquals(firstPath, items.findById(kira).orElseThrow().getImagePath());
        http.perform(delete("/api/items/" + kira + "/photos/" + first).with(csrf()).session(admin)).andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void optionsBelongToAMainProductOfTheSameShop() {
        ItemMaster gho = item("Gho", "1800.00", 5, null);
        ItemMaster ghoM = item("Gho, size M", "1800.00", 5, null);
        ItemMaster cheese = item("Yak cheese", "300.00", 5, 7L);

        options.apply(ghoM, gho.getItemId(), "Size M");
        items.save(ghoM);
        assertEquals(gho.getItemId(), ghoM.getVariantOf());

        ItemMaster ghoL = item("Gho, size L", "1900.00", 5, null);
        assertThrows(IllegalStateException.class, () -> options.apply(ghoL, ghoM.getItemId(), "Size L"), "not an option of an option");
        assertThrows(IllegalStateException.class, () -> options.apply(ghoL, gho.getItemId(), " "), "an option needs a name");
        assertThrows(IllegalStateException.class, () -> options.apply(cheese, gho.getItemId(), "Cheese"), "same shop only");
        assertThrows(IllegalStateException.class, () -> options.apply(gho, ghoL.getItemId(), "Main"), "a main product with options stays main");
        options.apply(gho, null, "Size S"); // the main product's own option name
        assertNull(gho.getVariantOf());
        assertEquals("Size S", gho.getVariantName());
    }

    @Test
    void aCouponTakesItsPartOffTheItemsWithinItsLimits() throws Exception {
        MockMvc http = http();
        User admin = person("coupons@offers.bt", "17920004", "ADMIN");
        User buyer = person("sonam@offers.bt", "17920005", "USER");
        MockHttpSession adminSession = signIn(http, admin);
        MockHttpSession buyerSession = signIn(http, buyer);
        Long rice = item("Red rice", "200.00", 20, null).getItemId();

        String form = "{\"code\":\"%s\",\"kind\":\"PERCENT\",\"value\":10,\"minOrder\":300,\"maxDiscount\":50,\"perCustomerLimit\":1,\"description\":\"Losar\"}";
        http.perform(post("/api/coupons/admin").with(csrf()).session(buyerSession).contentType(MediaType.APPLICATION_JSON)
                .content(form.formatted("DRUK10"))).andExpect(status().isForbidden());
        http.perform(post("/api/coupons/admin").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                .content(form.formatted("dr uk"))).andExpect(status().isBadRequest());
        http.perform(post("/api/coupons/admin").with(csrf()).session(adminSession).contentType(MediaType.APPLICATION_JSON)
                .content(form.formatted("druk10"))).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("DRUK10"));

        String cart = "{\"code\":\"druk10\",\"items\":[{\"itemId\":" + rice + ",\"quantity\":%d}]}";
        http.perform(post("/api/coupons/check").with(csrf()).session(buyerSession).contentType(MediaType.APPLICATION_JSON).content(cart.formatted(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("at least Nu. 300.00")));
        http.perform(post("/api/coupons/check").with(csrf()).session(buyerSession).contentType(MediaType.APPLICATION_JSON).content(cart.formatted(2)))
                .andExpect(jsonPath("$.discount").value(40.0)); // 10% of 400
        http.perform(post("/api/coupons/check").with(csrf()).session(buyerSession).contentType(MediaType.APPLICATION_JSON).content(cart.formatted(5)))
                .andExpect(jsonPath("$.discount").value(50.0)); // 10% of 1000, at most 50

        // the order: items 400 - 40, collected (no delivery fee)
        as(buyer);
        Order first = orders.createOrder(request(buyer, rice, 2, "DRUK10"));
        assertEquals("DRUK10", first.getCouponCode());
        assertEquals(0, new BigDecimal("40.00").compareTo(first.getCouponDiscount()));
        assertEquals(0, new BigDecimal("360.00").compareTo(first.getTotalAmount()));
        assertEquals(1, redemptions.countUses(redemptions.findAll().get(0).getCouponId()));
        assertThrows(IllegalStateException.class, () -> orders.createOrder(request(buyer, rice, 2, "DRUK10")), "once per customer");

        // a cancelled order frees its use
        Order cancelled = orderRepo.findById(first.getOrderId()).orElseThrow();
        cancelled.setOrderStatus("CANCELLED");
        orderRepo.save(cancelled);
        Order second = orders.createOrder(request(buyer, rice, 3, "DRUK10")); // 600, 10% = 60, at most 50
        assertEquals(0, new BigDecimal("550.00").compareTo(second.getTotalAmount()));

        // a return refunds what was paid after the coupon: 200 x 550/600 = 183.33 per bag
        Order done = orderRepo.findById(second.getOrderId()).orElseThrow();
        done.setOrderStatus("COMPLETED");
        orderRepo.save(done);
        as(admin);
        ReturnDTO.ReturnableOrder r = returns.returnable(done.getOrderId());
        assertTrue(r.eligible(), r.message());
        assertEquals(0, new BigDecimal("183.33").compareTo(r.lines().get(0).unitRefund()));
        ReturnDTO.ReturnView back = returns.createReturn(done.getOrderId(), new ReturnDTO.ReturnRequest("CHANGED_MIND", null, "ORIGINAL",
                List.of(new ReturnDTO.ReturnLineRequest(r.lines().get(0).orderItemId(), 3, false))), admin.getEmail());
        assertTrue(back.refundAmount().compareTo(new BigDecimal("550.05")) <= 0, "never more than was paid: " + back.refundAmount());
    }

    // ------------------------------------------------------------------ helpers

    private OrderRequestDTO request(User buyer, Long itemId, int quantity, String coupon) {
        OrderRequestDTO r = new OrderRequestDTO();
        r.setCustomerName(buyer.getName());
        r.setCustomerEmail(buyer.getEmail());
        r.setCustomerPhone(buyer.getPhone());
        r.setFulfilment("PICKUP");
        r.setCouponCode(coupon);
        r.setItems(List.of(new OrderRequestDTO.Item(itemId, quantity, null)));
        return r;
    }

    private ItemMaster item(String name, String price, int qty, Long sellerId) {
        ItemMaster i = new ItemMaster();
        i.setItemName(name);
        i.setSku("O-" + System.nanoTime());
        i.setSellingPrice(new BigDecimal(price));
        i.setMrp(new BigDecimal(price));
        i.setIsActive(true);
        i.setSellerId(sellerId);
        i.setCreatedAt(LocalDateTime.now());
        ItemMaster saved = items.save(i);
        InventoryStock s = new InventoryStock();
        s.setItemId(saved.getItemId());
        s.setCurrentQuantity(qty);
        stock.save(s);
        return saved;
    }

    private void as(User u) {
        String role = u.getRole().getName();
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        Permissions.defaultsFor(role).forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(u.getEmail(), null, authorities));
    }

    private static MockMultipartFile jpg() {
        return new MockMultipartFile("photo", "photo.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01});
    }

    private User person(String emailAddress, String phone, String role) {
        User u = new User();
        u.setName(emailAddress.substring(0, emailAddress.indexOf('@')));
        u.setEmail(emailAddress);
        u.setPhone(phone);
        u.setPassword(encoder.encode("secret-1"));
        u.setActive(true);
        u.setRole(roles.findByName(role).orElseThrow());
        return users.save(u);
    }

    private MockHttpSession signIn(MockMvc http, User u) throws Exception {
        return (MockHttpSession) http.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"secret-1\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}