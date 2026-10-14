package com.api.inventory.security;

import com.api.inventory.entity.User;
import com.api.inventory.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tells Spring Security who is calling, on every request.
 *
 * Login (AuthController) puts the email in the HTTP session. This filter looks the person up in the database
 * each time, so the CURRENT role and permissions are used: if an admin changes someone's role, or switches
 * the account off, it takes effect on the very next click (a switched-off account is signed out).
 *
 * It runs inside Spring Security's chain (see SecurityConfig), before the access rules are checked.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    /** Session attribute: the password's "last changed" time when this session signed in. */
    public static final String PASSWORD_STAMP = "pwdStamp";

    private final UserRepository users;
    private final AccessControlService access;

    public SessionAuthenticationFilter(UserRepository users, AccessControlService access) {
        this.users = users;
        this.access = access;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        HttpSession session = request.getSession(false); // never creates a session
        Object email = session == null ? null : session.getAttribute("userEmail");

        if (email instanceof String signedInEmail) {
            User user = users.findByEmail(signedInEmail).orElse(null);
            Object stamp = session.getAttribute(PASSWORD_STAMP);
            long sessionStamp = stamp instanceof Long l ? l : 0L;
            if (user == null || !user.isActive() || user.getRole() == null) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            } else if (sessionStamp < user.passwordStamp()) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            } else {
                String roleName = user.getRole().getName().trim().toUpperCase();
                if (!roleName.equals(session.getAttribute("userRole"))) {
                    session.setAttribute("userRole", roleName);
                }

                List<GrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));                 // for hasRole(...)
                for (String permission : access.permissionsOf(roleName)) {
                    authorities.add(new SimpleGrantedAuthority(permission));                     // for hasAuthority(...)
                }
                var principal = org.springframework.security.core.userdetails.User
                        .withUsername(user.getEmail()).password("").authorities(authorities).build();
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
