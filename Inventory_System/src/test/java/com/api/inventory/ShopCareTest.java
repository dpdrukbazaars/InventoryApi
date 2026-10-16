package com.api.inventory;

import com.api.inventory.entity.*;
import com.api.inventory.repository.*;
import com.api.inventory.service.EmailService;
import com.api.inventory.service.NotificationService;
import com.api.inventory.service.StockService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Saved addresses, "notify me when it is back", return requests (ask, approve, record the return: done) and a
 * seller's public shop page.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shopcare;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class ShopCareTest {

    @MockitoBean EmailService email;

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    @Autowired ItemMasterRepository items;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired SellerProfileRepository sellers;
    @Autowired StockService stock;
    @Autowired NotificationService notifications;

    private MockMvc http() {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
    }

    @Test
    void customersKeepTheirAddresses() throws Exception {
        MockMvc http = http();
        MockHttpSession pema = signIn(http, person("pema@care.bt", "17910001", "USER"));
        MockHttpSession other = signIn(http, person("other@care.bt", "17910002", "USER"));

        http.perform(post("/api/addresses").with(csrf()).session(pema).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Home\",\"phone\":\"17910001\",\"address\":\"Blue gate, Motithang\",\"latitude\":27.48,\"longitude\":89.63,\"pointLabel\":\"Your current location\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true)); // the first one
        String office = http.perform(post("/api/addresses").with(csrf()).session(pema).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Office\",\"phone\":\"17910009\",\"address\":\"Norzin Lam, 2nd floor\",\"makeDefault\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Long officeId = Long.valueOf(office.replaceAll(".*\"id\":(\\d+).*", "$1"));
        http.perform(post("/api/addresses").with(csrf()).session(pema).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Bad\",\"phone\":\"123\",\"address\":\"x\"}")).andExpect(status().isBadRequest());
        // somewhere outside Bhutan is not kept as a map point
        http.perform(post("/api/addresses").with(csrf()).session(pema).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Far\",\"phone\":\"17910001\",\"address\":\"Somewhere\",\"latitude\":51.5,\"longitude\":-0.1}"))
                .andExpect(jsonPath("$.latitude").doesNotExist());

        http.perform(get("/api/addresses").session(pema)).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].label").value("Office")).andExpect(jsonPath("$[0].isDefault").value(true));
        http.perform(get("/api/addresses").session(other)).andExpect(jsonPath("$", hasSize(0)));
        http.perform(delete("/api/addresses/" + officeId).with(csrf()).session(other)).andExpect(status().isBadRequest());
        // removing the default: another one becomes the default
        http.perform(delete("/api/addresses/" + officeId).with(csrf()).session(pema)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    void customersHearWhenASoldOutProductIsBack() throws Exception {
        MockMvc http = http();
        User karma = person("karma@care.bt", "17910003", "USER");
        MockHttpSession session = signIn(http, karma);
        Long ghee = product("CARE-GHEE", "Cow ghee", "250.00").getItemId();

        http.perform(post("/api/stock-alerts/" + ghee).with(csrf()).session(session)).andExpect(status().isOk());
        http.perform(post("/api/stock-alerts/" + ghee).with(csrf()).session(session)).andExpect(status().isOk()); // twice: one alert
        http.perform(get("/api/stock-alerts").session(session)).andExpect(jsonPath("$", contains(ghee.intValue())));

        stock.receive(ghee, 6, new BigDecimal("200.00"), null, null, null, null, "Arrived");

        assertTrue(notifications.latest("karma@care.bt", 10).stream()
                .anyMatch(n -> "BACK_IN_STOCK".equals(n.type()) && n.title().equals("Cow ghee is back in stock")));
        http.perform(get("/api/stock-alerts").session(session)).andExpect(jsonPath("$", hasSize(0))); // told once
        long before = notifications.latest("karma@care.bt", 50).size();
        stock.receive(ghee, 2, new BigDecimal("200.00"), null, null, null, null, "More"); // not sold out before: no news
        assertEquals(before, notifications.latest("karma@care.bt", 50).size());
    }

    @Test
    void aReturnRequestGoesFromAskedToDone() throws Exception {
        MockMvc http = http();
        User sonam = person("sonam@care.bt", "17910004", "USER");
        MockHttpSession customer = signIn(http, sonam);
        MockHttpSession stranger = signIn(http, person("stranger@care.bt", "17910005", "USER"));
        MockHttpSession admin = signIn(http, person("admin@care.bt", "17910006", "ADMIN"));
        Long rice = product("CARE-RICE", "Red rice", "100.00").getItemId();
        Order open = order(sonam, "CONFIRMED", rice, 2);
        Order done = order(sonam, "COMPLETED", rice, 3);
        Long line = orderItems.findAll().stream().filter(i -> i.getOrderId().equals(done.getOrderId())).findFirst().orElseThrow().getOrderItemId();

        http.perform(get("/api/orders/" + open.getOrderId() + "/return-request").session(customer))
                .andExpect(jsonPath("$.canRequest").value(false))
                .andExpect(jsonPath("$.message").value(containsString("once your whole order has reached you")));
        http.perform(get("/api/orders/" + done.getOrderId() + "/return-request").session(stranger)).andExpect(status().isNotFound());
        http.perform(get("/api/orders/" + done.getOrderId() + "/return-request").session(customer))
                .andExpect(jsonPath("$.canRequest").value(true)).andExpect(jsonPath("$.lines[0].quantityLeft").value(3))
                .andExpect(jsonPath("$.lines[0].itemName").value("Red rice"));

        String ask = "{\"reason\":\"DAMAGED\",\"details\":\"The bag was torn\",\"items\":[{\"orderItemId\":" + line + ",\"quantity\":%d}]}";
        http.perform(post("/api/orders/" + done.getOrderId() + "/return-request").with(csrf()).session(customer).contentType(MediaType.APPLICATION_JSON)
                .content(ask.formatted(4))).andExpect(status().isBadRequest()); // more than bought
        String made = http.perform(post("/api/orders/" + done.getOrderId() + "/return-request").with(csrf()).session(customer).contentType(MediaType.APPLICATION_JSON)
                        .content(ask.formatted(2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED")).andReturn().getResponse().getContentAsString();
        Long requestId = Long.valueOf(made.replaceAll("^\\{\"id\":(\\d+).*", "$1"));
        http.perform(post("/api/orders/" + done.getOrderId() + "/return-request").with(csrf()).session(customer).contentType(MediaType.APPLICATION_JSON)
                .content(ask.formatted(1))).andExpect(status().isBadRequest()); // one open request at a time
        assertTrue(notifications.latest("admin@care.bt", 10).stream().anyMatch(n -> "RETURN_REQUEST".equals(n.type())));

        // staff: the list, approve; customers cannot
        http.perform(get("/api/return-requests").session(customer)).andExpect(status().isForbidden());
        http.perform(get("/api/return-requests").session(admin)).andExpect(jsonPath("$[0].id").value(requestId))
                .andExpect(jsonPath("$[0].customerEmail").value("sonam@care.bt")).andExpect(jsonPath("$[0].lines[0].quantity").value(2));
        http.perform(post("/api/return-requests/" + requestId + "/decline").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest()); // a reason is needed
        http.perform(post("/api/return-requests/" + requestId + "/approve").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Our rider collects it tomorrow.\"}")).andExpect(jsonPath("$.status").value("APPROVED"));
        assertTrue(notifications.latest("sonam@care.bt", 10).stream()
                .anyMatch(n -> "RETURN_APPROVED".equals(n.type()) && n.body().equals("Our rider collects it tomorrow.")));

        // staff record the return: the request is done and the customer hears the amount
        http.perform(post("/api/orders/" + done.getOrderId() + "/returns").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"DAMAGED\",\"refundMethod\":\"ORIGINAL\",\"items\":[{\"orderItemId\":" + line + ",\"quantity\":2,\"restock\":false}]}"))
                .andExpect(status().isOk());
        http.perform(get("/api/orders/" + done.getOrderId() + "/return-request").session(customer))
                .andExpect(jsonPath("$.requests[0].status").value("DONE")).andExpect(jsonPath("$.requests[0].customerEmail").doesNotExist())
                .andExpect(jsonPath("$.lines[0].quantityLeft").value(1)).andExpect(jsonPath("$.canRequest").value(true));
        assertTrue(notifications.latest("sonam@care.bt", 10).stream()
                .anyMatch(n -> "RETURN_DONE".equals(n.type()) && n.body().equals("We refunded Nu. 200.00 to the account you paid from.")));
    }

    @Test
    void approvedSellersHaveAPublicShopPage() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).build();
        SellerProfile approved = seller(person("cheese@care.bt", "17910007", "USER"), "Bumthang Cheese", SellerProfile.APPROVED);
        SellerProfile waiting = seller(person("new@care.bt", "17910008", "USER"), "New Shop", SellerProfile.PENDING);
        ItemMaster cheese = product("CARE-CHEESE", "Yak cheese", "300.00");
        cheese.setSellerId(approved.getId());
        items.save(cheese);

        http.perform(get("/api/sellers/" + approved.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("Bumthang Cheese")).andExpect(jsonPath("$.town").value("Bumthang"))
                .andExpect(jsonPath("$.products").value(1)).andExpect(jsonPath("$.ratingCount").value(0));
        http.perform(get("/api/sellers/" + waiting.getId())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private Order order(User customer, String status, Long itemId, int quantity) {
        Order o = new Order();
        o.setCustomerName(customer.getName());
        o.setCustomerEmail(customer.getEmail());
        o.setCustomerPhone(customer.getPhone());
        o.setOrderStatus(status);
        o.setTotalAmount(new BigDecimal("100.00").multiply(BigDecimal.valueOf(quantity)));
        o.setDeliveryFee(BigDecimal.ZERO);
        Order saved = orders.save(o);
        OrderItem line = new OrderItem();
        line.setOrderId(saved.getOrderId());
        line.setItemId(itemId);
        line.setQuantity(quantity);
        line.setUnitPrice(new BigDecimal("100.00"));
        orderItems.save(line);
        return saved;
    }

    private SellerProfile seller(User user, String shop, String status) {
        SellerProfile s = new SellerProfile();
        s.setUser(user);
        s.setShopName(shop);
        s.setTown("Bumthang");
        s.setPickupAddress("Chamkhar town");
        s.setStatus(status);
        s.setCreatedAt(Instant.now());
        return sellers.save(s);
    }

    private ItemMaster product(String sku, String name, String price) {
        ItemMaster i = new ItemMaster();
        i.setSku(sku);
        i.setItemName(name);
        i.setSellingPrice(new BigDecimal(price));
        i.setMrp(new BigDecimal(price));
        i.setIsActive(true);
        return items.save(i);
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

