package com.api.inventory;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Real HTTP requests through the whole security chain (no network port needed):
 * the public shop window stays open, everything else needs sign-in and the right permission,
 * and switching an account off signs it out on the next click.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:securityhttp;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false", // the migrations are MySQL; tests build the tables from the code
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class SecurityHttpTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter securityChain;

    @Autowired
    com.api.inventory.repository.UserRepository users;

    @Autowired
    com.api.inventory.repository.RoleRepository roles;

    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder encoder;

    /** Spring Session: keeps sign-ins in the database and writes the cookie the browser gets. */
    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    Filter sessionFilter;

    MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
    }

    @Test
    void publicShopWindowIsOpen() throws Exception {
        http.perform(get("/api/items/allItems")).andExpect(status().isOk());
        http.perform(get("/api/marketplace/settings")).andExpect(status().isOk());
        // the cart shows the delivery price before anyone signs in
        http.perform(get("/api/delivery/areas")).andExpect(status().isOk());
        http.perform(post("/api/delivery/quote").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalFee").value(0));
    }

    @Test
    void everythingElseNeedsSignIn() throws Exception {
        http.perform(get("/api/reports/sales")).andExpect(status().isUnauthorized());          // was open before
        http.perform(get("/api/reports/sales/summary")).andExpect(status().isUnauthorized());
        http.perform(get("/api/admin/orders")).andExpect(status().isUnauthorized());
        http.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        http.perform(get("/api/pos/shifts/current")).andExpect(status().isUnauthorized());
        http.perform(get("/api/returns/summary")).andExpect(status().isUnauthorized());
        http.perform(get("/api/orders/1/returnable")).andExpect(status().isUnauthorized());
        http.perform(get("/api/customers/lookup").param("phone", "17000000")).andExpect(status().isUnauthorized());
        http.perform(get("/api/delivery/admin/areas")).andExpect(status().isUnauthorized());
        http.perform(get("/api/stock/batches")).andExpect(status().isUnauthorized());
        http.perform(put("/api/seller/location").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    /**
     * Signing in the way a browser does: through the session layer, keeping only the cookie.
     * Chrome throws away a cookie marked SameSite=None without Secure, which signed everyone out right after
     * "Welcome back" (an old cookie setting, 4 Oct 2026). The cookie must be one a browser keeps.
     */
    @Test
    void theSignInCookieIsOneABrowserKeeps() throws Exception {
        var user = new com.api.inventory.entity.User();
        user.setName("Pema");
        user.setEmail("pema@cookie.bt");
        user.setPhone("17888002");
        user.setPassword(encoder.encode("secret2"));
        user.setActive(true);
        user.setRole(roles.findByName("USER").orElseThrow());
        users.save(user);

        MockMvc browser = MockMvcBuilders.webAppContextSetup(context).addFilters(sessionFilter, securityChain).build();
        String setCookie = browser.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pema@cookie.bt\",\"password\":\"secret2\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie, "the sign-in sets a cookie");
        assertTrue(setCookie.startsWith("SESSION="), setCookie);
        assertFalse(setCookie.contains("SameSite=None") && !setCookie.contains("Secure"), "a browser would drop: " + setCookie);
        assertFalse(setCookie.contains("Domain="), setCookie);

        // Spring Boot configures the session cookie using
        // server.servlet.session.cookie.*.
        // Cross-site HTTPS deployments require SameSite=None and Secure=true.
        // Avoid introducing a custom cookie serializer that overrides these settings.
        var factory = ((org.springframework.context.ConfigurableApplicationContext) context).getBeanFactory();
        for (String name : factory.getBeanNamesForType(org.springframework.session.web.http.CookieSerializer.class)) {
            var definition = factory.getBeanDefinition(name);
            String origin = definition.getFactoryBeanName() + " " + definition.getResourceDescription();
            assertFalse(origin.contains("com.api.inventory") || origin.contains("com/api/inventory") || origin.contains("com\\api\\inventory"),
                    "our own cookie writer: " + origin);
        }
        assertEquals("true", context.getEnvironment().getProperty("server.servlet.session.cookie.http-only"));
        assertEquals("none", context.getEnvironment()
                .getProperty("server.servlet.session.cookie.same-site"));

        assertEquals("true", context.getEnvironment()
                .getProperty("server.servlet.session.cookie.secure"));

        String value = setCookie.substring("SESSION=".length(), setCookie.indexOf(';'));
        browser.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("SESSION", value)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("pema@cookie.bt"));
        browser.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotReachStaffToolsAndASwitchedOffAccountIsSignedOut() throws Exception {
        var user = new com.api.inventory.entity.User();
        user.setName("Karma");
        user.setEmail("karma@http.bt");
        user.setPhone("17888001");
        user.setPassword(encoder.encode("secret1"));
        user.setActive(true);
        user.setRole(roles.findByName("USER").orElseThrow());
        users.save(user);

        MockHttpSession session = (MockHttpSession) http.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"karma@http.bt\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray())
                .andReturn().getRequest().getSession(false);

        http.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        http.perform(get("/api/reports/sales").session(session)).andExpect(status().isForbidden());
        http.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        http.perform(get("/api/pos/shifts/current").session(session)).andExpect(status().isForbidden());
        http.perform(post("/api/orders/pos/sale").session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // wrong password: 401 with a message, not a server error
        http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"karma@http.bt\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());

        // broken JSON: a clear 400
        http.perform(post("/api/legal/terms/SELLER/accept").session(session).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        // the application form needs the multipart format: plain JSON gets a clear 415, not a server error
        http.perform(post("/api/marketplace/apply/seller").session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());

        // the agreements can be read without signing in
        http.perform(get("/api/legal/terms/SELLER")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        http.perform(get("/api/legal/terms/DRIVER")).andExpect(status().isOk());
        http.perform(get("/api/legal/admin/terms").session(session)).andExpect(status().isForbidden());
        http.perform(get("/api/stock/summary").session(session)).andExpect(status().isForbidden());   // costs are not for customers
        http.perform(get("/api/stock/profit").param("from", "2026-01-01").param("to", "2026-01-31").session(session)).andExpect(status().isForbidden());
        http.perform(post("/api/delivery/admin/areas").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"town\":\"Y\",\"latitude\":27.4,\"longitude\":89.6}")).andExpect(status().isForbidden());

        // an admin switches the account off: the very next request is anonymous, and sign-in is refused
        user = users.findByEmail("karma@http.bt").orElseThrow();
        user.setActive(false);
        users.save(user);
        http.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"karma@http.bt\",\"password\":\"secret1\"}"))
                .andExpect(status().isForbidden());
    }
}
