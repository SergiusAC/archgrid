package dev.archgrid.platform.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Translates identity-provider claims into Spring Security authorities.
 *
 * <p>Different identity providers put group membership in different claims: Keycloak
 * nests realm roles under {@code realm_access.roles}, Entra ID emits a flat {@code roles}
 * array, others use {@code groups}. Rather than hard-coding one vendor, the claim paths
 * are configuration, so an on-premise customer can point ArchGrid at whatever their IdP
 * already emits without a code change.
 *
 * <p>Standard OAuth2 {@code scope} authorities are intentionally not produced here: scopes
 * describe what a client application may do, not what a person may do, and conflating the
 * two is how authorization bugs get written.
 */
@Component
@EnableConfigurationProperties(RoleMappingProperties.class)
class RealmRoleGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final RoleMappingProperties properties;

    RealmRoleGrantedAuthoritiesConverter(RoleMappingProperties properties) {
        this.properties = properties;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String path : properties.roleClaimPaths()) {
            for (String role : readClaimPath(jwt, path)) {
                authorities.add(new SimpleGrantedAuthority(properties.authorityPrefix() + role));
            }
        }
        return authorities;
    }

    /** Resolves a dotted claim path such as {@code realm_access.roles} to a list of strings. */
    private static List<String> readClaimPath(Jwt jwt, String path) {
        String[] segments = path.split("\\.");
        Object current = jwt.getClaim(segments[0]);
        for (int i = 1; i < segments.length && current != null; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return List.of();
            }
            current = map.get(segments[i]);
        }
        if (current instanceof Collection<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    /** Exposed for diagnostics: the subject claim used as the stable actor identifier. */
    static String actorId(Jwt jwt) {
        return jwt.getClaimAsString(StandardClaimNames.SUB);
    }
}
