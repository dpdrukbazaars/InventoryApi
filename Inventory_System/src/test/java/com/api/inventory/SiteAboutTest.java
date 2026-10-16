package com.api.inventory;

import com.api.inventory.entity.User;
import com.api.inventory.repository.RoleRepository;
import com.api.inventory.repository.UserRepository;
import com.api.inventory.service.EmailService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The About page managed by staff: everyone can read it; only "site.manage" changes it (texts, live numbers on/off,
 * team members with photos, order, hidden people). In-memory database, photos kept in the database store.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:siteabout;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.files.store=database",
        "app.private-upload-dir=target/test-private-uploads-site",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class SiteAboutTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

    @MockitoBean EmailService email;

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;

    @Test
    void everyoneReadsItAndOnlySiteManagersChangeIt() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
        MockHttpSession admin = signIn(http, user("owner@site.bt", "ADMIN", "17700001"));
        MockHttpSession manager = signIn(http, user("manager@site.bt", "MANAGER", "17700002"));
        MockHttpSession customer = signIn(http, user("karma@site.bt", "USER", "17700003"));

        // anyone: the built-in wording, with the live numbers
        http.perform(get("/api/site/about")).andExpect(status().isOk())
                .andExpect(jsonPath("$.intro", containsString("DP DrukBazaars")))
                .andExpect(jsonPath("$.showNumbers").value(true))
                .andExpect(jsonPath("$.numbers.products").isNumber());

        // only site.manage may change it (a manager does not have it unless an admin gives it)
        http.perform(get("/api/site/admin/about")).andExpect(status().isUnauthorized());
        http.perform(get("/api/site/admin/about").session(customer)).andExpect(status().isForbidden());
        http.perform(get("/api/site/admin/about").session(manager)).andExpect(status().isForbidden());
        http.perform(get("/api/site/admin/about").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults['about.mission']", containsString("mission")));

        // the texts
        http.perform(put("/api/site/admin/about").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"intro\":\"\",\"mission\":\"m\",\"vision\":\"v\"}")).andExpect(status().isBadRequest());
        http.perform(put("/api/site/admin/about").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intro\":\"We are a Thimphu marketplace.\",\"mission\":\"Simple shopping.\",\"vision\":\"Every town.\",\"showNumbers\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedBy").value("owner@site.bt"));
        http.perform(get("/api/site/about")).andExpect(jsonPath("$.intro").value("We are a Thimphu marketplace."))
                .andExpect(jsonPath("$.showNumbers").value(false)).andExpect(jsonPath("$.numbers").doesNotExist());

        // the team: a photo is checked by its content, and stored under the person's own name
        http.perform(multipart("/api/site/admin/team").with(csrf()).file(new MockMultipartFile("photo", "me.png", "image/png", "not a picture".getBytes()))
                .param("name", "Sonam").param("role", "Packer").session(admin)).andExpect(status().isBadRequest());
        http.perform(multipart("/api/site/admin/team").with(csrf()).param("name", " ").param("role", "Packer").session(admin))
                .andExpect(status().isBadRequest());
        String body = http.perform(multipart("/api/site/admin/team").with(csrf()).file(new MockMultipartFile("photo", "me.png", "image/png", PNG))
                        .param("name", "Sonam Wangmo").param("role", "Packing and delivery").param("bio", "Packs every order with care.")
                        .session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Integer sonam = JsonPath.read(body, "$.id");
        String photo = JsonPath.read(body, "$.photo");
        assertTrue(photo.startsWith("/uploads/team-" + sonam + "-") && photo.endsWith(".png"), photo);
        http.perform(get(photo)).andExpect(status().isOk());

        Integer pema = JsonPath.read(http.perform(multipart("/api/site/admin/team").with(csrf()).param("name", "Pema").param("role", "Accounts")
                .param("visible", "false").session(admin)).andReturn().getResponse().getContentAsString(), "$.id");
        // hidden people are not on the page
        http.perform(get("/api/site/about")).andExpect(jsonPath("$.team[*].name", hasItem("Sonam Wangmo")))
                .andExpect(jsonPath("$.team[*].name", not(hasItem("Pema"))));

        // a different order; a stale list is refused
        http.perform(put("/api/site/admin/team/order").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + sonam + "]}")).andExpect(status().isBadRequest());
        http.perform(put("/api/site/admin/team/order").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + pema + "," + sonam + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Pema"));

        // back to initials: the uploaded photo is deleted
        http.perform(multipart(HttpMethod.PUT, "/api/site/admin/team/" + sonam).with(csrf()).param("name", "Sonam Wangmo")
                        .param("role", "Packing").param("removePhoto", "true").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photo").doesNotExist()).andExpect(jsonPath("$.role").value("Packing"));
        http.perform(get(photo)).andExpect(status().isNotFound());

        http.perform(delete("/api/site/admin/team/" + pema).with(csrf()).session(manager)).andExpect(status().isForbidden());
        http.perform(delete("/api/site/admin/team/" + pema).with(csrf()).session(admin)).andExpect(status().isOk());
        List<String> names = JsonPath.read(http.perform(get("/api/site/admin/about").session(admin))
                .andReturn().getResponse().getContentAsString(), "$.team[*].name");
        assertEquals(List.of("Sonam Wangmo"), names);
    }

    @Test
    void theShopsContactDetailsAndLinksAreManagedToo() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
        MockHttpSession admin = signIn(http, user("owner2@site.bt", "ADMIN", "17700011"));
        MockHttpSession manager = signIn(http, user("manager2@site.bt", "MANAGER", "17700012"));

        // everyone: the built-in details (no Instagram yet: no icon)
        http.perform(get("/api/site/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("77269712"))
                .andExpect(jsonPath("$.email").value("dpdrukbazaars@gmail.com"))
                .andExpect(jsonPath("$.instagram").value(""));

        String good = "{\"phone\":\"17 11 22 33\",\"email\":\"hello@drukbazaars.bt\",\"address\":\"Norzin Lam, Thimphu\","
                + "\"facebook\":\"\",\"instagram\":\"https://www.instagram.com/drukbazaars\",\"youtube\":\"\",\"tiktok\":\"\"}";
        http.perform(put("/api/site/admin/info").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(good)).andExpect(status().isUnauthorized());
        http.perform(put("/api/site/admin/info").with(csrf()).session(manager).contentType(MediaType.APPLICATION_JSON).content(good)).andExpect(status().isForbidden());
        // a link must be a full https address; the phone digits; the email an email
        http.perform(put("/api/site/admin/info").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content(good.replace("https://www.instagram.com/drukbazaars", "javascript:alert(1)"))).andExpect(status().isBadRequest());
        http.perform(put("/api/site/admin/info").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content(good.replace("17 11 22 33", "call us"))).andExpect(status().isBadRequest());
        http.perform(put("/api/site/admin/info").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content(good.replace("hello@drukbazaars.bt", "hello"))).andExpect(status().isBadRequest());

        http.perform(put("/api/site/admin/info").with(csrf()).session(admin).contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isOk()).andExpect(jsonPath("$.address").value("Norzin Lam, Thimphu"));
        // an emptied link stays empty (the Facebook icon goes away), the new one shows
        http.perform(get("/api/site/info"))
                .andExpect(jsonPath("$.phone").value("17 11 22 33"))
                .andExpect(jsonPath("$.facebook").value(""))
                .andExpect(jsonPath("$.instagram").value("https://www.instagram.com/drukbazaars"));
    }

    private MockHttpSession signIn(MockMvc http, User u) throws Exception {
        return (MockHttpSession) http.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"secret-1\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private User user(String email, String role, String phone) {
        User u = new User();
        u.setName(email.substring(0, email.indexOf('@')));
        u.setEmail(email);
        u.setPhone(phone);
        u.setPassword(encoder.encode("secret-1"));
        u.setActive(true);
        u.setRole(roles.findByName(role).orElseThrow());
        return users.save(u);
    }
}
