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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads X-User-* headers set by the API gateway after JWT validation.
 * Populates SecurityContext and sets GatewayUser as a request attribute.
 *
 * Permissions are read from X-User-Permissions header (Spring Cloud Gateway)
 * or extracted from the JWT payload (Envoy Gateway).
 */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FILTERED_ROLES = Set.of("offline_access", "uma_authorization");
    private static final String FILTERED_ROLES_PREFIX = "default-roles-";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userUuid = request.getHeader("X-User-UUID");

        if (userUuid != null && !userUuid.isBlank()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(userUuid);
            } catch (IllegalArgumentException _) {
                filterChain.doFilter(request, response);
                return;
            }

            String permissions = request.getHeader("X-User-Permissions");
            List<String> permList;

            if (permissions != null && !permissions.isBlank()) {
                permList = Arrays.asList(permissions.split(","));
            } else {
                permList = extractPermissionsFromJwt(request);
            }

            var user = new GatewayUser(
                    uuid,
                    request.getHeader("X-User-Email"),
                    permList
            );

            request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);

            var authorities = permList.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }

    private List<String> extractPermissionsFromJwt(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return List.of();
        }

        try {
            String[] parts = authHeader.substring(7).split("\\.");
            if (parts.length < 2) return List.of();

            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode jwt = MAPPER.readTree(payload);
            JsonNode roles = jwt.path("realm_access").path("roles");

            if (!roles.isArray()) return List.of();

            List<String> filtered = new ArrayList<>();
            for (JsonNode role : roles) {
                String r = role.asText();
                if (!FILTERED_ROLES.contains(r) && !r.startsWith(FILTERED_ROLES_PREFIX)) {
                    filtered.add(r);
                }
            }
            return filtered;
        } catch (Exception _) {
            return List.of();
        }
    }
}
