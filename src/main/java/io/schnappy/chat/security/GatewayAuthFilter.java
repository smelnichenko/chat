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

/**
 * Reads X-User-* headers set by the API gateway after JWT validation.
 * Populates SecurityContext and sets GatewayUser as a request attribute.
 * Primary identifier is X-User-UUID (Keycloak subject). The Long userId
 * is resolved via {@link UserIdResolver} from the local user table.
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter extends OncePerRequestFilter {

    private final UserCacheService userCacheService;
    private final UserIdResolver userIdResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userUuid = request.getHeader("X-User-UUID");

        if (userUuid != null && !userUuid.isBlank()) {
            String permissions = request.getHeader("X-User-Permissions");
            List<String> permList = permissions != null && !permissions.isBlank()
                    ? Arrays.asList(permissions.split(","))
                    : List.of();

            String email = request.getHeader("X-User-Email");
            Long userId = userIdResolver.resolve(userUuid);

            var user = new GatewayUser(
                    userUuid,
                    email,
                    permList,
                    userId
            );

            request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);
            if (userId != null && email != null) {
                userCacheService.cacheUser(userId, email, true);
            }

            var authorities = permList.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }
}
