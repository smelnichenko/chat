package io.schnappy.chat.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads X-User-* headers set by the Istio gateway after JWT validation.
 * Falls back to extracting claims directly from the JWT payload.
 * Populates SecurityContext and sets GatewayUser as a request attribute.
 */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> FILTERED_ROLES = Set.of("offline_access", "uma_authorization");
    private static final String FILTERED_ROLES_PREFIX = "default-roles-";
    private static final Duration KNOWN_USER_TTL = Duration.ofMinutes(5);

    private final UserProvisioner userProvisioner;
    private final Map<UUID, Instant> knownUsers = new ConcurrentHashMap<>();

    public GatewayAuthFilter(UserProvisioner userProvisioner) {
        this.userProvisioner = userProvisioner;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userUuid = request.getHeader("X-User-UUID");
        String userEmail = request.getHeader("X-User-Email");
        JsonNode jwtPayload = parseJwtPayload(request);

        if ((userUuid == null || userUuid.isBlank()) && jwtPayload != null) {
            userUuid = jwtPayload.path("sub").asText(null);
            userEmail = jwtPayload.path("email").asText(userEmail);
        }

        if (userUuid != null && !userUuid.isBlank()) {
            authenticateUser(request, userUuid, userEmail, jwtPayload);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticateUser(HttpServletRequest request, String userUuid, String userEmail, JsonNode jwtPayload) {
        UUID uuid;
        try {
            uuid = UUID.fromString(userUuid);
        } catch (IllegalArgumentException _) {
            return;
        }

        List<String> permList = resolvePermissions(request, jwtPayload);
        ensureUserProvisioned(uuid, userEmail, permList);

        var user = new GatewayUser(uuid, userEmail, permList);
        request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);

        var authorities = permList.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private List<String> resolvePermissions(HttpServletRequest request, JsonNode jwtPayload) {
        String permissions = request.getHeader("X-User-Permissions");
        if (permissions != null && !permissions.isBlank()) {
            return Arrays.asList(permissions.split(","));
        }
        return extractRolesFromJwt(jwtPayload);
    }

    private JsonNode parseJwtPayload(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            String[] parts = authHeader.substring(BEARER_PREFIX.length()).split("\\.");
            if (parts.length < 2) return null;
            return MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception _) {
            return null;
        }
    }

    private List<String> extractRolesFromJwt(JsonNode jwtPayload) {
        if (jwtPayload == null) return List.of();
        JsonNode roles = jwtPayload.path("realm_access").path("roles");
        if (!roles.isArray()) return List.of();

        List<String> filtered = new ArrayList<>();
        for (JsonNode role : roles) {
            String r = role.asText();
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
