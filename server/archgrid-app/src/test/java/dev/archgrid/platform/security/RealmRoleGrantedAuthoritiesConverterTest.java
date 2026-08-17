package dev.archgrid.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The mapping from identity-provider claims to authorities.
 *
 * <p>Previously only exercised through tests that injected authorities directly, which meant
 * the one piece of code that actually reads a real token was never run. A live token from
 * Keycloak then produced 403 on every mutation.
 */
class RealmRoleGrantedAuthoritiesConverterTest {

    private static final RoleMappingProperties DEFAULTS = new RoleMappingProperties(null, null);

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256");
        claims.forEach(builder::claim);
        return builder.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    @Test
    @DisplayName("Keycloak nests realm roles under realm_access.roles")
    void maps_keycloak_realm_roles() {
        var converter = new RealmRoleGrantedAuthoritiesConverter(DEFAULTS);

        var authorities = converter.convert(jwt(Map.of(
                "sub", "user-1", "realm_access", Map.of("roles", List.of("owner", "viewer", "contributor")))));

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_OWNER", "ROLE_VIEWER", "ROLE_CONTRIBUTOR");
    }

    @Test
    @DisplayName("a flat roles claim, as Entra ID emits it, works too")
    void maps_a_flat_roles_claim() {
        var converter = new RealmRoleGrantedAuthoritiesConverter(DEFAULTS);

        var authorities = converter.convert(jwt(Map.of("sub", "user-1", "roles", List.of("admin"))));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void maps_a_groups_claim() {
        var converter = new RealmRoleGrantedAuthoritiesConverter(DEFAULTS);

        var authorities = converter.convert(jwt(Map.of("sub", "user-1", "groups", List.of("viewer"))));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_VIEWER");
    }

    @Test
    @DisplayName("claim paths and prefix are configuration, so a customer's IdP shape is a setting")
    void honours_configured_claim_paths() {
        var converter = new RealmRoleGrantedAuthoritiesConverter(
                new RoleMappingProperties(List.of("custom.deeply.nested"), "AUTH_"));

        var authorities = converter.convert(jwt(Map.of(
                "sub", "user-1", "custom", Map.of("deeply", Map.of("nested", List.of("owner"))))));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("AUTH_OWNER");
    }

    @Test
    void a_token_without_any_role_claim_yields_no_authorities() {
        var converter = new RealmRoleGrantedAuthoritiesConverter(DEFAULTS);

        assertThat(converter.convert(jwt(Map.of("sub", "user-1")))).isEmpty();
    }

    @Test
    @DisplayName("a claim that is not a collection is ignored rather than throwing")
    void tolerates_unexpected_claim_shapes() {
        var converter = new RealmRoleGrantedAuthoritiesConverter(DEFAULTS);

        assertThat(converter.convert(jwt(Map.of("sub", "user-1", "roles", "owner")))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("sub", "user-1", "realm_access", "nonsense"))))
                .isEmpty();
    }

    @Test
    @DisplayName("defaults apply when nothing is configured")
    void properties_default_to_the_common_claim_shapes() {
        assertThat(DEFAULTS.roleClaimPaths()).containsExactly("realm_access.roles", "roles", "groups");
        assertThat(DEFAULTS.authorityPrefix()).isEqualTo("ROLE_");
    }
}
