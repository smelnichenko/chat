package io.schnappy.chat.security;

import io.schnappy.chat.service.UserCacheService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Reads X-User-* headers set by the API gateway after JWT validation.
 * Populates SecurityContext and sets GatewayUser as a request attribute.
 * Primary identifier is X-User-UUID (Keycloak subject).
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter extends OncePerRequestFilter {

    private final UserCacheService userCacheService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userUuidStr = request.getHeader("X-User-UUID");

        if (userUuidStr != null && !userUuidStr.isBlank()) {
            try {
                UUID userUuid = UUID.fromString(userUuidStr);
                String permissions = request.getHeader("X-User-Permissions");
                List<String> permList = permissions != null && !permissions.isBlank()
                        ? Arrays.asList(permissions.split(","))
                        : List.of();

                String email = request.getHeader("X-User-Email");

                var user = new GatewayUser(userUuid, email, permList);

                request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);
                if (email != null) {
                    userCacheService.cacheUser(userUuid, email, true);
                }

                var authorities = permList.stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (IllegalArgumentException _) {
                // Invalid UUID — skip authentication
            }
        }

        filterChain.doFilter(request, response);
    }
}
