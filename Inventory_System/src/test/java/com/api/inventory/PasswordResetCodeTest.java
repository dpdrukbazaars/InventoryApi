
package com.api.inventory;

import com.api.inventory.entity.User;
import com.api.inventory.repository.RoleRepository;
import com.api.inventory.repository.UserRepository;
import com.api.inventory.service.EmailService;
import com.api.inventory.service.SmsService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * "Forgot password" by a 6-digit code, by text message or email: the right code gives a one-time ticket to choose
 * a new password; wrong codes run out; unknown numbers get the same answer; only offered ways can be used.
 * Messages are caught instead of sent, so the codes can be read from them. In-memory database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:resetcodes;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class PasswordResetCodeTest {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @MockitoBean EmailService email;
    @MockitoBean SmsService sms;

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;

    MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
        when(email.isAvailable()).thenReturn(true);
        when(sms.isAvailable()).thenReturn(true);
    }

    @Test
    void aCodeByTextMessageChoosesANewPasswordOnce() throws Exception {
        user("tashi@codes.bt", "17400001", "old-secret");

        // an unknown number gets the same answer, and nothing is sent
        send("{\"method\":\"sms\",\"phone\":\"17499999\"}").andExpect(status().isOk());
        verify(sms, never()).send(eq("17499999"), anyString());

        // typed with the country code and spaces: still found
        send("{\"method\":\"sms\",\"phone\":\"+975 17 40 00 01\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.resendAfter").value(60));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sms).send(eq("17400001"), text.capture());
        String code = sixDigits(text.getValue());

        // a code for a number without an account never works
        verifyCode("{\"method\":\"sms\",\"phone\":\"17499999\",\"code\":\"" + code + "\"}").andExpect(status().isBadRequest());
        String wrong = code.equals("000000") ? "111111" : "000000";
        verifyCode("{\"method\":\"sms\",\"phone\":\"17400001\",\"code\":\"" + wrong + "\"}").andExpect(status().isBadRequest());
        verifyCode("{\"method\":\"sms\",\"phone\":\"17400001\",\"code\":\"12\"}").andExpect(status().isBadRequest());

        String ticket = JsonPath.read(verifyCode("{\"method\":\"sms\",\"phone\":\"17400001\",\"code\":\"" + code + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");

        // the code works once
        verifyCode("{\"method\":\"sms\",\"phone\":\"17400001\",\"code\":\"" + code + "\"}").andExpect(status().isBadRequest());

        reset(ticket, "new-secret-7").andExpect(status().isOk()).andExpect(jsonPath("$.email").value("tashi@codes.bt"));
        reset(ticket, "again-secret-8").andExpect(status().is4xxClientError()); // the ticket works once too

        login("tashi@codes.bt", "old-secret").andExpect(status().isUnauthorized());
        login("tashi@codes.bt", "new-secret-7").andExpect(status().isOk());

        // the owner is told by email
        verify(email).sendEmail(eq("tashi@codes.bt"), contains("password was changed"), anyString());
    }

    @Test
    void anEmailCodeRunsOutAfterFiveWrongTriesAndTheLinkStopsAfterAReset() throws Exception {
        user("sonam@codes.bt", "17400002", "old-secret");

        send("{\"method\":\"email\",\"email\":\"sonam@codes.bt\"}").andExpect(status().isOk());

        // asked again at once: the first code is still on its way, no second email
        send("{\"method\":\"email\",\"email\":\"sonam@codes.bt\"}").andExpect(status().isOk());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email, times(1)).sendEmail(eq("sonam@codes.bt"), subject.capture(), body.capture());

        String code = sixDigits(body.getValue());
        assertTrue(subject.getValue().startsWith(code), "the code is in the subject, to read it from the notification");

        Matcher link = Pattern.compile("reset-password\\?token=([A-Za-z0-9_-]+)").matcher(body.getValue());
        assertTrue(link.find(), "the email also holds a link");

        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            verifyCode("{\"method\":\"email\",\"email\":\"sonam@codes.bt\",\"code\":\"" + wrong + "\"}").andExpect(status().isBadRequest());
        }

        // five wrong tries: even the right code no longer works
        verifyCode("{\"method\":\"email\",\"email\":\"sonam@codes.bt\",\"code\":\"" + code + "\"}").andExpect(status().isBadRequest());

        // the link still works, and after the reset it stops
        http.perform(get("/api/auth/reset-password/check").param("token", link.group(1)))
                .andExpect(jsonPath("$.valid").value(true));

        reset(link.group(1), "fresh-secret-3").andExpect(status().isOk());

        http.perform(get("/api/auth/reset-password/check").param("token", link.group(1)))
                .andExpect(jsonPath("$.valid").value(false));

        login("sonam@codes.bt", "fresh-secret-3").andExpect(status().isOk());
    }

    @Test
    void onlyTheWaysTheServerCanUseAreOffered() throws Exception {
        when(email.isAvailable()).thenReturn(false);

        http.perform(get("/api/auth/forgot-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(false))
                .andExpect(jsonPath("$.sms").value(true));

        send("{\"method\":\"email\",\"email\":\"someone@codes.bt\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot send emails")));

        send("{\"method\":\"pigeon\",\"email\":\"someone@codes.bt\"}").andExpect(status().isBadRequest());
        send("{\"method\":\"sms\",\"phone\":\"1234\"}").andExpect(status().isBadRequest());

        verify(email, never()).sendEmail(anyString(), anyString(), anyString());
    }

    // ================= Helpers =================

    private ResultActions send(String json) throws Exception {
        return http.perform(post("/api/auth/forgot-password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions verifyCode(String json) throws Exception {
        return http.perform(post("/api/auth/forgot-password/verify")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return http.perform(post("/api/auth/reset-password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return http.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private static String sixDigits(String text) {
        Matcher m = SIX_DIGITS.matcher(text);
        assertTrue(m.find(), "the message holds a 6-digit code");
        return m.group(1);
    }

    private User user(String email, String phone, String password) {
        User u = new User();
        u.setName(email.substring(0, email.indexOf('@')));
        u.setEmail(email);
        u.setPhone(phone);
        u.setPassword(encoder.encode(password));
        u.setActive(true);
        u.setRole(roles.findByName("USER").orElseThrow());
        return users.save(u);
    }
}
