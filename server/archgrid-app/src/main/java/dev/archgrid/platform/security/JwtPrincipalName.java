package dev.archgrid.platform.security;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * What to call the person behind a token.
 *
 * <p>Used for one thing: attributing a commit. Spring names a JWT principal by {@code sub}, which
 * at Keycloak and most other IdPs is a UUID — and a change log saying that {@code
 * 80c279ca-1bf9-46f7-9940-16586abf4f5f} renamed the payment service is a change log nobody reads.
 * Phase 5 is where that stops being cosmetic.
 *
 * <p>Claims in order of how much they say about a person, {@code sub} last. Each is a convention
 * rather than a guarantee — an IdP may emit none of them — and an ugly attribution is better than
 * an absent one, so the fallback chain never ends in null.
 *
 * <p>Deliberately not used for authorisation. Authorities come from realm roles
 * ({@link RealmRoleGrantedAuthoritiesConverter}); this is a label, and a label that an IdP lets a
 * user edit must never be a permission.
 */
final class JwtPrincipalName {

    private static final List<String> CLAIMS = List.of("preferred_username", "email", "name");

    private JwtPrincipalName() {}

    static String of(Jwt jwt) {
        for (String claim : CLAIMS) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return jwt.getSubject();
    }
}
