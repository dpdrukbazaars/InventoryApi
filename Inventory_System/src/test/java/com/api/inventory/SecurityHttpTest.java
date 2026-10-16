
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP security tests through the Spring Security filter chain.
 *
 * Checks:
 * 1. Public shop endpoints remain accessible.
 * 2. Protected endpoints require authentication.
 * 3. Browser login and session cookies work.
 * 4. Customers cannot access restricted staff operations.
 * 5. Missing or invalid CSRF tokens are rejected.
 * 6. CORS allows trusted origins and rejects unknown origins.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:securityhttp;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",

        // Create Spring Session JDBC tables in this test database.
        "spring.session.jdbc.initialize-schema=always",

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

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    Filter sessionFilter;

    MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(securityChain)
                .build();
    }

    // ============================================================
    // TEST 1: Public shop endpoints remain accessible
    // ============================================================

    @Test
    void publicShopWindowIsOpen() throws Exception {

        http.perform(get("/api/items/allItems"))
                .andExpect(status().isOk());

        http.perform(get("/api/marketplace/settings"))
                .andExpect(status().isOk());

        http.perform(get("/api/delivery/areas"))
                .andExpect(status().isOk());

        // Public POST requests still require CSRF protection.
        http.perform(post("/api/delivery/quote")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFee").value(0));
    }

    // ============================================================
    // TEST 2: Protected endpoints require authentication
    // ============================================================

    @Test
    void everythingElseNeedsSignIn() throws Exception {

        http.perform(get("/api/reports/sales"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/reports/sales/summary"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/admin/orders"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/pos/shifts/current"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/returns/summary"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/orders/1/returnable"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/customers/lookup")
                        .param("phone", "17000000"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/delivery/admin/areas"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/stock/batches"))
                .andExpect(status().isUnauthorized());

        // Include CSRF so this tests authentication, not token absence.
        http.perform(put("/api/seller/location")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // TEST 3: Login creates a usable browser session cookie
    // ============================================================

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

        MockMvc browser = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(sessionFilter, securityChain)
                .build();

        String setCookie = browser.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"pema@cookie.bt\","
                                                + "\"password\":\"secret2\"}"
                                )
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("Set-Cookie");

        assertNotNull(setCookie, "The sign-in response sets a cookie.");

        assertTrue(
                setCookie.startsWith("SESSION="),
                setCookie
        );

        assertFalse(
                setCookie.contains("SameSite=None")
                        && !setCookie.contains("Secure"),
                "A browser would drop this cookie: " + setCookie
        );

        assertFalse(
                setCookie.contains("Domain="),
                setCookie
        );

        // Avoid a custom application cookie serializer overriding
        // Spring Boot's session cookie settings.
        var factory =
                ((org.springframework.context.ConfigurableApplicationContext) context)
                        .getBeanFactory();

        for (String name : factory.getBeanNamesForType(
                org.springframework.session.web.http.CookieSerializer.class)) {

            var definition = factory.getBeanDefinition(name);

            String origin = definition.getFactoryBeanName()
                    + " "
                    + definition.getResourceDescription();

            assertFalse(
                    origin.contains("com.api.inventory")
                            || origin.contains("com/api/inventory")
                            || origin.contains("com\\api\\inventory"),
                    "Custom cookie writer detected: " + origin
            );
        }

        assertEquals(
                "true",
                context.getEnvironment().getProperty(
                        "server.servlet.session.cookie.http-only"
                )
        );

        assertEquals(
                "none",
                context.getEnvironment().getProperty(
                        "server.servlet.session.cookie.same-site"
                )
        );

        assertEquals(
                "true",
                context.getEnvironment().getProperty(
                        "server.servlet.session.cookie.secure"
                )
        );

        String value = setCookie.substring(
                "SESSION=".length(),
                setCookie.indexOf(';')
        );

        // The issued cookie must authenticate the next request.
        browser.perform(
                        get("/api/auth/me")
                                .cookie(new jakarta.servlet.http.Cookie(
                                        "SESSION",
                                        value
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("pema@cookie.bt"));

        // Without the cookie, the caller must remain anonymous.
        browser.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // TEST 4: Customer permissions and account deactivation
    // ============================================================

    @Test
    void customerCannotReachStaffToolsAndASwitchedOffAccountIsSignedOut()
            throws Exception {

        var user = new com.api.inventory.entity.User();

        user.setName("Karma");
        user.setEmail("karma@http.bt");
        user.setPhone("17888001");
        user.setPassword(encoder.encode("secret1"));
        user.setActive(true);
        user.setRole(roles.findByName("USER").orElseThrow());

        users.save(user);

        // A valid CSRF token allows the login controller to run.
        MockHttpSession session = (MockHttpSession) http.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"karma@http.bt\","
                                                + "\"password\":\"secret1\"}"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray())
                .andReturn()
                .getRequest()
                .getSession(false);

        http.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk());

        http.perform(get("/api/reports/sales").session(session))
                .andExpect(status().isForbidden());

        http.perform(get("/api/admin/users").session(session))
                .andExpect(status().isForbidden());

        http.perform(get("/api/pos/shifts/current").session(session))
                .andExpect(status().isForbidden());

        // A customer must not be able to perform staff operations.
        http.perform(post("/api/orders/pos/sale")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // Wrong password should be 401, not a CSRF error.
        http.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\":\"karma@http.bt\","
                                        + "\"password\":\"nope\"}"
                        ))
                .andExpect(status().isUnauthorized());

        // Invalid JSON should produce 400 after CSRF validation.
        http.perform(post("/api/legal/terms/SELLER/accept")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        // JSON instead of multipart should produce 415.
        http.perform(post("/api/marketplace/apply/seller")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());

        // Public agreements can be viewed without login.
        http.perform(get("/api/legal/terms/SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        http.perform(get("/api/legal/terms/DRIVER"))
                .andExpect(status().isOk());

        // Customer must not access restricted information.
        http.perform(get("/api/legal/admin/terms")
                        .session(session))
                .andExpect(status().isForbidden());

        http.perform(get("/api/stock/summary")
                        .session(session))
                .andExpect(status().isForbidden());

        http.perform(get("/api/stock/profit")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .session(session))
                .andExpect(status().isForbidden());

        http.perform(post("/api/delivery/admin/areas")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"name\":\"X\","
                                        + "\"town\":\"Y\","
                                        + "\"latitude\":27.4,"
                                        + "\"longitude\":89.6}"
                        ))
                .andExpect(status().isForbidden());

        // Disable the user.
        user = users.findByEmail("karma@http.bt").orElseThrow();

        user.setActive(false);
        users.save(user);

        // Existing session must no longer authenticate the account.
        http.perform(get("/api/auth/me")
                        .session(session))
                .andExpect(status().isUnauthorized());

        // Even with a valid CSRF token, disabled login is rejected.
        http.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\":\"karma@http.bt\","
                                        + "\"password\":\"secret1\"}"
                        ))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // TEST 5: Missing and invalid CSRF tokens are rejected
    // ============================================================

    @Test
    void rejectsRequestsWithoutCsrfToken() throws Exception {

        // No CSRF token.
        http.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // Deliberately invalid CSRF token.
        http.perform(post("/api/auth/login")
                        .with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // GET requests do not require a CSRF token.
        http.perform(get("/api/items/allItems"))
                .andExpect(status().isOk());
    }

    // ============================================================
    // TEST 6: CORS restrictions
    // ============================================================

    @Test
    void corsAllowsLocalFrontendAndRejectsUnknownOrigin()
            throws Exception {

        http.perform(options("/api/items/allItems")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://localhost:4200"
                ))
                .andExpect(header().string(
                        "Access-Control-Allow-Credentials",
                        "true"
                ));

        http.perform(options("/api/items/allItems")
                        .header("Origin", "https://unknown.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
