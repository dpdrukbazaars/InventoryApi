package com.api.inventory;

import com.api.inventory.entity.ItemMaster;
import com.api.inventory.entity.User;
import com.api.inventory.repository.ItemMasterRepository;
import com.api.inventory.repository.RoleRepository;
import com.api.inventory.repository.UserRepository;
import com.api.inventory.service.EmailService;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The wishlist (save, save again, remove, only your own, only when signed in) and the share page that gives
 * WhatsApp and Facebook a preview of a product (name, price, photo; nothing for a product shoppers may not see).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shoplists;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "app.public-url=https://shop.example.bt/",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class ShopListsTest {

    @MockitoBean EmailService email;

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    @Autowired ItemMasterRepository items;

    @Test
    void customersKeepAWishlistOfTheirOwn() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
        Long tea = product("WL-TEA", "Suja tea", "120.00", true, null).getItemId();
        Long rice = product("WL-RICE", "Red rice", "90.00", true, null).getItemId();
        MockHttpSession pema = signIn(http, person("pema@lists.bt", "17900001"));
        MockHttpSession dorji = signIn(http, person("dorji@lists.bt", "17900002"));

        http.perform(get("/api/wishlist")).andExpect(status().isUnauthorized());
        http.perform(post("/api/wishlist/" + tea).with(csrf()).session(pema))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itemId").value(tea));
        http.perform(post("/api/wishlist/" + tea).with(csrf()).session(pema))
                .andExpect(status().isOk()); // twice: still one
        http.perform(post("/api/wishlist/" + rice).with(csrf()).session(pema))
                .andExpect(status().isOk());
        http.perform(post("/api/wishlist/999999").with(csrf()).session(pema))
                .andExpect(status().isBadRequest());

        http.perform(get("/api/wishlist").session(pema)).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].itemId").value(rice)); // newest first
        http.perform(get("/api/wishlist").session(dorji)).andExpect(jsonPath("$", hasSize(0)));
        http.perform(delete("/api/wishlist/" + tea).with(csrf()).session(dorji))
                .andExpect(status().isOk()); // not theirs: nothing happens
        http.perform(delete("/api/wishlist/" + tea).with(csrf()).session(pema))
                .andExpect(status().isOk());
        http.perform(get("/api/wishlist").session(pema)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].itemId").value(rice));
    }

    @Test
    void aSharedLinkShowsThePreviewThenTheProduct() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).build();
        ItemMaster honey = product("SH-HONEY", "Wild <honey> & wax", "350.00", true, "/uploads/item-9-abc.jpg");
        Long hidden = product("SH-OFF", "Old stock", "10.00", false, null).getItemId();

        http.perform(get("/api/share/products/" + honey.getItemId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<meta property=\"og:title\" content=\"Wild &lt;honey&gt; &amp; wax · Nu. 350.00\">")))
                .andExpect(content().string(containsString("<meta property=\"og:image\" content=\"https://shop.example.bt/uploads/item-9-abc.jpg\">")))
                .andExpect(content().string(containsString("url=https://shop.example.bt/products/" + honey.getItemId() + "\"")));
        http.perform(get("/api/share/products/" + hidden))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Old stock"))))
                .andExpect(content().string(containsString("url=https://shop.example.bt/products\"")));
    }

    private ItemMaster product(String sku, String name, String price, boolean active, String photo) {
        ItemMaster i = new ItemMaster();
        i.setSku(sku);
        i.setItemName(name);
        i.setSellingPrice(new BigDecimal(price));
        i.setMrp(new BigDecimal(price));
        i.setIsActive(active);
        i.setImagePath(photo);
        return items.save(i);
    }

    private User person(String emailAddress, String phone) {
        User u = new User();
        u.setName(emailAddress.substring(0, emailAddress.indexOf('@')));
        u.setEmail(emailAddress);
        u.setPhone(phone);
        u.setPassword(encoder.encode("secret-1"));
        u.setActive(true);
        u.setRole(roles.findByName("USER").orElseThrow());
        return users.save(u);
    }

    private MockHttpSession signIn(MockMvc http, User u) throws Exception {
        return (MockHttpSession) http.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"secret-1\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}
