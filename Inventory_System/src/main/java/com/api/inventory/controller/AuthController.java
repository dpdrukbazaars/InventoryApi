// src/main/java/com/api/inventory/controller/AuthController.java
package com.api.inventory.controller;

import com.api.inventory.dto.CurrentUserDTO;
import com.api.inventory.dto.SignupRequest;
import com.api.inventory.entity.Role;
import com.api.inventory.entity.User;
import com.api.inventory.repository.UserRepository;
import com.api.inventory.repository.RoleRepository;
import com.api.inventory.security.AccessControlService;
import com.api.inventory.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;


import jakarta.servlet.http.HttpServletResponse;




/**
 * Sign up, sign in, "who am I", sign out and change password.
 * Answers never contain the password hash. Problems with what the person typed are 4xx with a plain message
 * (the screens show that text), never a 500 error.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Same rule as the sign-up screen. */
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final String WRONG_LOGIN = "That email and password do not match.";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AccessControlService access;

    @Autowired
    private com.api.inventory.service.PasswordResetService passwordReset;

    @Autowired
    private com.api.inventory.service.LegalTermsService legal;

    @Autowired
    private com.api.inventory.service.CustomerService customerService;

    @Autowired
    private com.api.inventory.service.AccountDetailsService accountDetails;

    @Autowired
    private com.api.inventory.service.ProductPhotos photos;

    @PostMapping("/signup")
    @org.springframework.transaction.annotation.Transactional // the account and the terms acceptance are saved together
    public ResponseEntity<?> signup(@RequestBody SignupRequest request, HttpServletRequest httpRequest) {
        String name = trim(request.getName());
        String email = trim(request.getEmail());
        String phone = trim(request.getPhone());
        String password = request.getPassword() == null ? "" : request.getPassword();

        // The browser checks these too, but the server decides.
        if (name.isEmpty()) return badRequest("Enter your full name.");
        if (!email.matches("^\\S+@\\S+\\.\\S+$")) return badRequest("Enter a valid email address.");
        if (!phone.matches("^[0-9]{8}$")) return badRequest("Enter an 8-digit phone number.");
        if (password.length() < MIN_PASSWORD_LENGTH) return badRequest("Use at least " + MIN_PASSWORD_LENGTH + " characters for the password.");

        // the customer must agree to the CURRENT Terms of Use and Privacy (recorded below, with time and IP)
        Integer currentTerms = legal.current("CUSTOMER").getVersion();
        if (request.getAcceptedTermsVersion() == null) return badRequest("Please read and accept the Terms of Use and Privacy.");
        if (!request.getAcceptedTermsVersion().equals(currentTerms)) {
            return badRequest("The Terms of Use were updated. Please reload the page and accept the latest version.");
        }

        if (userRepository.existsByEmail(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("This email is already registered. Please sign in.");
        }
        if (userRepository.existsByPhone(phone)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("This phone number is already registered.");
        }

        Role customerRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new IllegalStateException("The customer role (USER) is missing."));

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(customerRole);
        User saved = userRepository.save(user);

        legal.accept(saved.getEmail(), "CUSTOMER", currentTerms, httpRequest);
        customerService.linkUser(saved); // their customer record (joined to earlier counter visits with the same phone)
        return ResponseEntity.ok(toDto(saved));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String email = trim(request.getEmail());
        String password = request.getPassword() == null ? "" : request.getPassword();

        User user = userRepository.findByEmail(email).orElse(null);
        // Same answer for "no such email" and "wrong password", so nobody can find out which emails exist.
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(WRONG_LOGIN);
        }
        if (!user.isActive()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("This account is switched off. Please contact the shop.");
        }
        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);

        // Fresh session id at sign-in, so a session id someone saw before sign-in is worth nothing afterwards.
        HttpSession old = httpRequest.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = httpRequest.getSession(true);

        session.setAttribute("userEmail", user.getEmail());
        session.setAttribute("userRole", user.getRole().getName());
        session.setAttribute(
                com.api.inventory.security.SessionAuthenticationFilter.PASSWORD_STAMP,
                user.passwordStamp()
        );

// Build authentication using the user's current role and permissions.
        String roleName = user.getRole().getName()
                .trim()
                .toUpperCase(Locale.ROOT);

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));

        for (String permission : access.permissionsOf(roleName)) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }

        var principal = org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password("")
                .authorities(authorities)
                .build();

        SecurityContext context = SecurityContextHolder.createEmptyContext();

        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        authorities
                )
        );

        SecurityContextHolder.setContext(context);
        // Persist authentication during login, not on each normal GET request.
        HttpSessionSecurityContextRepository contextRepository =
                new HttpSessionSecurityContextRepository();

        contextRepository.saveContext(
                context,
                httpRequest,
                httpResponse
        );

        return ResponseEntity.ok(toDto(user));

    }


    @GetMapping("/me")
    public ResponseEntity<?> getMe() {
        String email = CurrentUser.email();

        User user = email == null
                ? null
                : userRepository.findByEmail(email).orElse(null);

        if (user == null || !user.isActive()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Not authenticated");
        }

        return ResponseEntity.ok(toDto(user));
    }


    // ================= My profile: their own details and photo =================

    /** currentPassword: needed only to change the email (it is what the person signs in with). */
    public record ProfileChange(String name, String email, String phone, String currentPassword) {
    }

    /**
     * The signed-in person changes their own name, phone and email (the same checks as when staff do it: a valid,
     * unused email; an 8-digit, unused phone). Their orders and the rest move with a new email, and they stay signed in.
     */
    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> updateMe(@RequestBody ProfileChange body, HttpServletRequest request) {
        User user = userRepository.findByEmail(CurrentUser.email()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Please sign in.");
        }
        String newEmail = body == null || body.email() == null ? user.getEmail() : body.email().trim();
        if (!newEmail.equalsIgnoreCase(user.getEmail())
                && (body.currentPassword() == null || !passwordEncoder.matches(body.currentPassword(), user.getPassword()))) {
            return badRequest("To change your email, type your current password.");
        }
        String phone = body == null || body.phone() == null ? user.getPhone() : body.phone();
        String name = body == null || body.name() == null ? user.getName() : body.name();
        accountDetails.change(user, new com.api.inventory.service.AccountDetailsService.Change(name, newEmail, phone), true);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute("userEmail", user.getEmail()); // this browser stays signed in with the new email
        }
        return ResponseEntity.ok(toDto(user));
    }

    /** Their profile photo: JPG, PNG, WEBP or GIF, at most 5 MB (the website sends it small and square). */
    @PostMapping(value = "/me/photo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> uploadPhoto(@RequestPart("photo") org.springframework.web.multipart.MultipartFile photo) {
        User user = userRepository.findByEmail(CurrentUser.email()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Please sign in.");
        }
        if (photo == null || photo.isEmpty()) {
            return badRequest("Choose a photo.");
        }
        user.setPhotoPath(photos.saveUserPhoto(user.getId(), photo, user.getPhotoPath()));
        userRepository.save(user);
        return ResponseEntity.ok(toDto(user));
    }

    @DeleteMapping("/me/photo")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> removePhoto() {
        User user = userRepository.findByEmail(CurrentUser.email()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Please sign in.");
        }
        photos.removeUserPhoto(user.getId(), user.getPhotoPath());
        user.setPhotoPath(null);
        userRepository.save(user);
        return ResponseEntity.ok(toDto(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok("Logged out");
    }

    /** The signed-in person changes their own password (for example one an admin gave them). */
    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        User user = userRepository.findByEmail(CurrentUser.email()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Please sign in.");
        }

        String current = request.getCurrentPassword() == null ? "" : request.getCurrentPassword();
        String next = request.getNewPassword() == null ? "" : request.getNewPassword();

        if (!passwordEncoder.matches(current, user.getPassword())) {
            return badRequest("Your current password is not correct.");
        }
        if (next.length() < MIN_PASSWORD_LENGTH) {
            return badRequest("Use at least " + MIN_PASSWORD_LENGTH + " characters for the new password.");
        }
        if (next.equals(current)) {
            return badRequest("The new password must be different from the current one.");
        }

        user.setPassword(passwordEncoder.encode(next));
        user.setPasswordChangedAt(java.time.Instant.now());
        userRepository.save(user);
        // this browser stays signed in; every other place where the account was signed in is signed out
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.setAttribute(com.api.inventory.security.SessionAuthenticationFilter.PASSWORD_STAMP, user.passwordStamp());
        }
        return ResponseEntity.ok(Map.of("message", "Your password has been changed. Other devices were signed out."));
    }

    // ================= Forgot password =================

    /**
     * Which ways can send the reset code: email, text message (SMS). A way the server cannot use (no email account
     * or SMS provider set up, for example on free hosting) is not offered; with neither, the page says to ask the shop.
     */
    @GetMapping("/forgot-password")
    public Map<String, Boolean> forgotPasswordAvailable() {
        var ways = passwordReset.ways();
        return Map.of("email", ways.email(), "sms", ways.sms());
    }

    /**
     * Step 1: a 6-digit code by email ({method: "email", email}) or text message ({method: "sms", phone}).
     * The answer is the same whether or not the email or number has an account.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        Map<String, String> b = body == null ? Map.of() : body;
        String channel = com.api.inventory.service.PasswordResetService.channel(b.get("method"));
        passwordReset.sendCode(b.get("method"), b.get("email"), b.get("phone"), clientIp(httpRequest));
        String message = com.api.inventory.entity.PasswordResetCode.EMAIL.equals(channel)
                ? "If an account uses that email, we have sent it a 6-digit code and a link. The code works for 10 minutes."
                : "If an account uses that phone number, we have sent it a 6-digit code by text message. It works for 10 minutes.";
        return ResponseEntity.ok(Map.of("message", message,
                "resendAfter", com.api.inventory.service.PasswordResetService.RESEND_AFTER_SECONDS));
    }

    /** Step 2: the code from the message. The right one gives a ticket (15 minutes, once) for step 3. */
    @PostMapping("/forgot-password/verify")
    public Map<String, String> verifyResetCode(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        Map<String, String> b = body == null ? Map.of() : body;
        String ticket = passwordReset.verifyCode(b.get("method"), b.get("email"), b.get("phone"), b.get("code"), clientIp(httpRequest));
        return Map.of("token", ticket);
    }

    /** Is this link still good? (The page shows the form, or explains that the link expired.) */
    @GetMapping("/reset-password/check")
    public Map<String, Boolean> checkResetLink(@RequestParam String token) {
        return Map.of("valid", passwordReset.isValid(token));
    }

    /** Step 3 (or the email link): the new password. The answer holds the account's email, to sign in with. */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String accountEmail = passwordReset.reset(body == null ? null : body.get("token"), body == null ? null : body.get("newPassword"), MIN_PASSWORD_LENGTH);
        return ResponseEntity.ok(Map.of("message", "Your password has been changed. Sign in with the new password.",
                "email", accountEmail));
    }

    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr(); // behind the proxy, server.forward-headers-strategy makes this the visitor's address
    }

    private CurrentUserDTO toDto(User user) {
        CurrentUserDTO dto = new CurrentUserDTO(user.getEmail(), user.getName(), user.getPhone(), user.getRole().getName());
        dto.setPermissions(access.permissionsOf(user.getRole().getName()).stream().sorted().toList());
        dto.setPhotoPath(user.getPhotoPath());
        return dto;
    }

    private static ResponseEntity<String> badRequest(String message) {
        return ResponseEntity.badRequest().body(message);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static class LoginRequest {
        private String email;
        private String password;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class ChangePasswordRequest {
        private String currentPassword;
        private String newPassword;

        public String getCurrentPassword() { return currentPassword; }
        public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }
}
