package dev.archgrid.platform.security;

import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * The API is a stateless OAuth2 resource server: the browser client performs the
 * Authorization Code + PKCE flow against the customer's identity provider and presents a
 * bearer token on every call.
 *
 * <p>ArchGrid never stores passwords and never issues its own tokens. On-premise
 * customers expect their own IdP to be the single place where identity lives, and any
 * local user store becomes an audit finding during a security review.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Actuator endpoints are matched first so that the liveness and readiness probes stay
     * reachable when the identity provider is unavailable — otherwise an IdP outage makes
     * the orchestrator kill healthy pods.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain actuatorSecurity(HttpSecurity http, WebEndpointProperties endpoints) throws Exception {
        String base = endpoints.getBasePath();
        return http.securityMatcher(base + "/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                base + "/health", base + "/health/**", base + "/info")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        return http.securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        // Return 401 rather than a redirect: the caller is a script or an
                        // SPA fetch, never a browser navigation.
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Safe to disable only because this chain is stateless and accepts no
                // cookie-based authentication. Revisit if a session ever appears here.
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(RealmRoleGrantedAuthoritiesConverter roles) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
