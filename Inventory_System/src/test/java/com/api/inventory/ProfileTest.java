package com.api.inventory;

import com.api.inventory.entity.User;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** My profile: a person changes their own name, phone and email (a new email needs the password), and their photo. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:profile;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.private-upload-dir=target/test-private-uploads",
        "app.files.store=database", // the test photos stay in the test database
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class ProfileTest {

    @MockitoBean EmailService email;

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;

    @Test
    void peopleEditTheirOwnProfileAndPhoto() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();

        User tashi = person("tashi@profile.bt", "17930001");
        person("other@profile.bt", "17930002");

        MockHttpSession me = signIn(http, "tashi@profile.bt");

        http.perform(put("/api/auth/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().is4xxClientError());

        // name and phone: no password needed; someone else's phone is refused
        http.perform(put("/api/auth/me")
                        .with(csrf())
                        .session(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tashi Wangmo\",\"email\":\"tashi@profile.bt\",\"phone\":\"17930009\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tashi Wangmo"))
                .andExpect(jsonPath("$.phone").value("17930009"));

        http.perform(put("/api/auth/me")
                        .with(csrf())
                        .session(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tashi Wangmo\",\"email\":\"tashi@profile.bt\",\"phone\":\"17930002\"}"))
                .andExpect(status().isBadRequest());

        // a new email needs the current password; then this browser stays signed in with it
        String newEmail = "{\"name\":\"Tashi Wangmo\",\"email\":\"tashi.w@profile.bt\",\"phone\":\"17930009\"%s}";

        http.perform(put("/api/auth/me")
                        .with(csrf())
                        .session(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newEmail.formatted("")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("current password")));

        http.perform(put("/api/auth/me")
                        .with(csrf())
                        .session(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newEmail.formatted(",\"currentPassword\":\"wrong\"")))
                .andExpect(status().isBadRequest());

        http.perform(put("/api/auth/me")
                        .with(csrf())
                        .session(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newEmail.formatted(",\"currentPassword\":\"secret-1\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("tashi.w@profile.bt"));

        http.perform(get("/api/auth/me").session(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("tashi.w@profile.bt"));

        signIn(http, "tashi.w@profile.bt");

        // the photo: a picture only, replaced by the next one, and removable
        MockMultipartFile jpg = new MockMultipartFile(
                "photo",
                "me.jpg",
                "image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01}
        );

        http.perform(multipart("/api/auth/me/photo")
                        .with(csrf())
                        .file(new MockMultipartFile("photo", "x.txt", "text/plain", "hi".getBytes()))
                        .session(me))
                .andExpect(status().isBadRequest());

        http.perform(multipart("/api/auth/me/photo")
                        .with(csrf())
                        .file(jpg)
                        .session(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoPath").value(startsWith("/uploads/user-" + tashi.getId() + "-")));

        http.perform(delete("/api/auth/me/photo")
                        .with(csrf())
                        .session(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoPath").doesNotExist());
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

    private MockHttpSession signIn(MockMvc http, String emailAddress) throws Exception {
        return (MockHttpSession) http.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + emailAddress + "\",\"password\":\"secret-1\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }
}
