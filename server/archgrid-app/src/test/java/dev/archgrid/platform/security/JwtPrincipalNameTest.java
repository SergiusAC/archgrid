package dev.archgrid.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who a commit is attributed to.
 *
 * <p>Exercised here rather than through the API, for the same reason the role converter is: every
 * other test injects an authentication directly and so never reads a real token. That is how the
 * role mapping shipped broken once, and the change log is where the same blind spot would show up
 * as a page of UUIDs.
 */
class JwtPrincipalNameTest {

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256");
        claims.forEach(builder::claim);
        return builder.issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    @DisplayName("Keycloak's preferred_username is the name, not the subject UUID")
    void prefers_the_username() {
        Jwt token = jwt(Map.of("sub", "80c279ca-1bf9-46f7-9940-16586abf4f5f", "preferred_username", "architect"));

        assertThat(JwtPrincipalName.of(token)).isEqualTo("architect");
    }

    @Test
    @DisplayName("an IdP that emits only an email is attributed by it")
    void falls_back_to_the_email() {
        Jwt token = jwt(Map.of("sub", "user-1", "email", "anna@example.com"));

        assertThat(JwtPrincipalName.of(token)).isEqualTo("anna@example.com");
    }

    @Test
    @DisplayName("a blank claim is no claim")
    void ignores_an_empty_username() {
        Jwt token = jwt(Map.of("sub", "user-1", "preferred_username", "   ", "name", "Anna Ivanova"));

        assertThat(JwtPrincipalName.of(token)).isEqualTo("Anna Ivanova");
    }

    /**
     * The chain never ends in null. An unattributed commit is worse than an ugly one, and a
     * null-named principal is what {@code JwtAuthenticationConverter.setPrincipalClaimName} would
     * have produced here.
     */
    @Test
    @DisplayName("with none of the claims, the subject still attributes the commit")
    void falls_back_to_the_subject() {
        Jwt token = jwt(Map.of("sub", "user-1"));

        assertThat(JwtPrincipalName.of(token)).isEqualTo("user-1");
    }
}
