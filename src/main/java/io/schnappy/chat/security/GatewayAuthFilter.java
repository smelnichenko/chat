package io.schnappy.chat.security;

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
import java.util.Arrays;
import java.util.List;

/**
 * Reads X-User-* headers set by the API gateway after JWT validation.
 * Populates SecurityContext and sets GatewayUser as a request attribute.
 */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader("X-User-ID");

        if (userId != null && !userId.isBlank()) {
            String permissions = request.getHeader("X-User-Permissions");
            List<String> permList = permissions != null && !permissions.isBlank()
                    ? Arrays.asList(permissions.split(","))
                    : List.of();

            var user = new GatewayUser(
                    Long.parseLong(userId),
                    request.getHeader("X-User-UUID"),
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
}
