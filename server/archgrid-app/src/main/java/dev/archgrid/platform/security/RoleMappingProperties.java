package dev.archgrid.platform.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to find group or role membership in the tokens issued by the customer's identity
 * provider.
 *
 * @param roleClaimPaths dotted paths into the JWT claim set; every string value found at
 *     any of them becomes an authority. Defaults cover Keycloak realm roles, Keycloak
 *     client roles are not included because they require the client id.
 * @param authorityPrefix prefix applied to every mapped authority. {@code ROLE_} keeps
 *     {@code hasRole(...)} working in method security.
 */
@ConfigurationProperties(prefix = "archgrid.security")
record RoleMappingProperties(List<String> roleClaimPaths, String authorityPrefix) {

    RoleMappingProperties {
        if (roleClaimPaths == null || roleClaimPaths.isEmpty()) {
            roleClaimPaths = List.of("realm_access.roles", "roles", "groups");
        }
        if (authorityPrefix == null) {
            authorityPrefix = "ROLE_";
        }
    }
}
