package com.api.inventory;

import com.api.inventory.entity.User;
import com.api.inventory.repository.CustomerRepository;
import com.api.inventory.repository.RoleRepository;
import com.api.inventory.repository.UserRepository;
import com.api.inventory.service.EmailService;
import com.api.inventory.service.LegalTermsService;
import com.api.inventory.service.NotificationService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * An admin corrects a person's name, email and phone: what the person owns follows the new email, they sign in with
 * it (the old sign-in ends), duplicates are refused, and only people managers (not for their own account) may do it.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:accountdetails;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class AccountDetailsTest {

    @MockitoBean EmailService email;

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired CustomerRepository customers;
    @Autowired PasswordEncoder encoder;
    @Autowired LegalTermsService legal;
    @Autowired NotificationService notifications;

    @Test
    void anAdminCorrectsAPersonsEmailAndPhone() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
        MockHttpSession admin = signIn(http, staff("owner@acc.bt", "ADMIN", "17800001"), "secret-1");
        MockHttpSession manager = signIn(http, staff("manager@acc.bt", "MANAGER", "17800002"), "secret-1");
        staff("taken@acc.bt", "USER", "17800009");

        // A customer signs up (customer record + accepted terms), and gets a notification.
        int terms = legal.current("CUSTOMER").getVersion();
        http.perform(post("/api/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Karma Dorji\",\"email\":\"karma.old@acc.bt\",\"phone\":\"17800003\",\"password\":\"karma-pass\",\"acceptedTermsVersion\":" + terms + "}"))
                .andExpect(status().isOk());
        User karma = users.findByEmail("karma.old@acc.bt").orElseThrow();
        notifications.user("karma.old@acc.bt", new NotificationService.Note("TEST", "Hello", "A note", "/"), false);
        MockHttpSession karmaOld = signIn(http, karma, "karma-pass");

        String body = "{\"name\":\"Karma Tshering Dorji\",\"email\":\"karma.new@acc.bt\",\"phone\":\"17800004\"}";

        // Only people managers, and not for their own account.
        http.perform(put("/api/admin/users/" + karma.getId())
                        .with(csrf())
                        .session(manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        Long ownerId = users.findByEmail("owner@acc.bt").orElseThrow().getId();
        http.perform(put("/api/admin/users/" + ownerId)
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Owner\",\"email\":\"owner2@acc.bt\",\"phone\":\"17800001\"}"))
                .andExpect(status().isForbidden());

        // Another account's email or phone, and a bad phone: refused.
        http.perform(put("/api/admin/users/" + karma.getId())
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("karma.new@acc.bt", "TAKEN@acc.bt")))
                .andExpect(status().is4xxClientError());

        http.perform(put("/api/admin/users/" + karma.getId())
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("17800004", "17800009")))
                .andExpect(status().is4xxClientError());

        http.perform(put("/api/admin/users/" + karma.getId())
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("17800004", "1234")))
                .andExpect(status().is4xxClientError());

        http.perform(put("/api/admin/users/" + karma.getId())
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("karma.new@acc.bt"))
                .andExpect(jsonPath("$.phone").value("17800004"))
                .andExpect(jsonPath("$.name").value("Karma Tshering Dorji"));

        // What Karma owns came along.
        assertTrue(legal.hasAcceptedCurrent("karma.new@acc.bt", "CUSTOMER"), "the accepted terms follow the new email");
        assertEquals(1, notifications.latest("karma.new@acc.bt", 10).size(), "so do the notifications");
        var record = customers.findAll().stream().filter(c -> karma.getId().equals(c.getUserId())).findFirst().orElseThrow();
        assertEquals("karma.new@acc.bt", record.getEmail());
        assertEquals("17800004", record.getPhone());

        // The old sign-in has ended; the new email works with the same password, the old one does not.
        http.perform(get("/api/auth/me").session(karmaOld))
                .andExpect(status().isUnauthorized());

        http.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"karma.old@acc.bt\",\"password\":\"karma-pass\"}"))
                .andExpect(status().isUnauthorized());

        http.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"karma.new@acc.bt\",\"password\":\"karma-pass\"}"))
                .andExpect(status().isOk());

        // Both addresses are told.
        verify(email).sendEmail(eq("karma.old@acc.bt"), contains("sign-in email was changed"), anyString());
        verify(email).sendEmail(eq("karma.new@acc.bt"), contains("sign-in email was changed"), anyString());
    }

    private MockHttpSession signIn(MockMvc http, User u, String password) throws Exception {
        return (MockHttpSession) http.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    private User staff(String emailAddress, String role, String phone) {
        User u = new User();
        u.setName(emailAddress.substring(0, emailAddress.indexOf('@')));
        u.setEmail(emailAddress);
        u.setPhone(phone);
        u.setPassword(encoder.encode("secret-1"));
        u.setActive(true);
        u.setRole(roles.findByName(role).orElseThrow());
        return users.save(u);
    }
}
