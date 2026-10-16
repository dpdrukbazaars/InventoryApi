package com.api.inventory.config;

import com.api.inventory.repository.UserRepository;
import com.api.inventory.security.AccessControlService;
import com.api.inventory.security.SessionAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * The outer wall. Two layers:
 *   1. Here: everything under /api needs a signed-in person, except the short list of public addresses below.
 *      A new endpoint that someone forgets to protect is therefore closed, not open.
 *   2. On each endpoint: @PreAuthorize("hasAuthority('...')") decides which permission is needed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository users, AccessControlService access) throws Exception {
        http
                // Use the CorsConfigurationSource bean from CorsConfig (allows the Angular dev server)
                .cors(Customizer.withDefaults())

                // Require CSRF tokens for browser requests that change data.
                // Exclude payment gateway callbacks because they use
                // server-to-server signature verification instead.
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                request ->
                                        "POST".equalsIgnoreCase(request.getMethod())
                                                && request.getRequestURI()
                                                .matches(".*/api/online-payments/callback/[^/]+$")
                        ))

                // Login is our own /api/auth/login; the session is created there
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                // Who is calling: read from the session and the database on every request
                .addFilterBefore(new SessionAuthenticationFilter(users, access), AnonymousAuthenticationFilter.class)

                .authorizeHttpRequests(auth -> auth
                        // Angular needs a CSRF token before signing in.
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()

                        // public: signing in and up
                        .requestMatchers("/api/auth/login", "/api/auth/signup", "/api/auth/logout", "/api/auth/me",
                                "/api/auth/forgot-password", "/api/auth/forgot-password/verify", "/api/auth/reset-password",
                                "/api/auth/reset-password/check").permitAll()
                        // public: the shop window
                        .requestMatchers(HttpMethod.GET, "/api/items/allItems", "/api/items/*", "/api/items/stock/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/marketplace/settings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/delivery/areas").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/delivery/quote").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/online-payments/options").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/online-payments/callback/*").permitAll()   // the gateway, checked by its signature
                        .requestMatchers(HttpMethod.GET, "/api/legal/terms/*", "/api/legal/terms/*/versions/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/contact").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/reviews/products/*", "/api/reviews/summary", "/api/reviews/service").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/site/about", "/api/site/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/share/**").permitAll()   // link previews for WhatsApp, Facebook
                        .requestMatchers(HttpMethod.GET, "/api/sellers/*").permitAll()   // a seller's shop page
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // everything else in the API needs a signed-in person (and then its own permission)
                        .requestMatchers("/api/**").authenticated()
                        // pictures, the old static pages, health check
                        .anyRequest().permitAll()
                )

                // a clear 401 (not a login page redirect) when someone is not signed in
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Please sign in.\"}");
                }))

                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.sameOrigin())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));

        return http.build();
    }
}
