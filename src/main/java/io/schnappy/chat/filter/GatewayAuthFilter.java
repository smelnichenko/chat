package io.schnappy.chat.filter;

import io.schnappy.chat.security.GatewayUser;
import io.schnappy.chat.security.UserProvisioner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication filter that independently validates the forwarded Keycloak
 * access token.
 *
 * <p>The Istio gateway validates north-south tokens, but east-west traffic
 * (pod → service, bypassing the gateway) is not covered by that edge check. To
 * remove the in-mesh impersonation path, this filter verifies the Bearer
 * token's signature against the Keycloak JWKS itself (via {@link JwtDecoder})
 * and derives identity (UUID, email) and roles from the <em>validated</em>
 * claims — the spoofable {@code X-User-*} request headers are no longer trusted.
 *
 * <p>A request without a Bearer token proceeds unauthenticated (public routes
 * are permitted by the security config); a request with an invalid or forged
 * token is left unauthenticated and rejected downstream with 401.
 */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> FILTERED_ROLES = Set.of("offline_access", "uma_authorization");
    private static final String FILTERED_ROLES_PREFIX = "default-roles-";
    private static final Duration KNOWN_USER_TTL = Duration.ofMinutes(5);

    private final JwtDecoder jwtDecoder;
    private final UserProvisioner userProvisioner;
    private final Map<UUID, Instant> knownUsers = new ConcurrentHashMap<>();

    public GatewayAuthFilter(JwtDecoder jwtDecoder, UserProvisioner userProvisioner) {
        this.jwtDecoder = jwtDecoder;
        this.userProvisioner = userProvisioner;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null) {
            try {
                authenticate(request, jwtDecoder.decode(token));
            } catch (JwtException e) {
                // Forged / expired / wrong-issuer token: leave unauthenticated so
                // the security chain rejects protected routes with 401.
                log.debug("Rejected bearer token: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void authenticate(HttpServletRequest request, Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(subject);
        } catch (IllegalArgumentException _) {
            return;
        }

        String email = jwt.getClaimAsString("email");
        List<String> permissions = extractRoles(jwt);
        ensureUserProvisioned(uuid, email, permissions);

        var user = new GatewayUser(uuid, email, permissions);
        request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);

        var authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private List<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>();
        for (Object role : roles) {
            String r = String.valueOf(role);
            if (!FILTERED_ROLES.contains(r) && !r.startsWith(FILTERED_ROLES_PREFIX)) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    private void ensureUserProvisioned(UUID uuid, String email, List<String> permissions) {
        Instant lastSeen = knownUsers.get(uuid);
        if (lastSeen != null && lastSeen.isAfter(Instant.now().minus(KNOWN_USER_TTL))) {
            return;
        }
        userProvisioner.provisionUser(uuid.toString(), email, permissions);
        knownUsers.put(uuid, Instant.now());
    }
}
