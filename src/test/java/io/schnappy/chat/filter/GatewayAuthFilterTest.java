package io.schnappy.chat.filter;

import io.schnappy.chat.security.GatewayUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayAuthFilterTest {

    private static final UUID TEST_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String BEARER = "Bearer header.payload.sig";

    @Mock
    private JwtDecoder jwtDecoder;
    @Mock
    private FilterChain filterChain;

    private GatewayAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GatewayAuthFilter(jwtDecoder, (uuid, email, roles) -> {});
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validToken_populatesSecurityContextFromValidatedClaims() throws Exception {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt(TEST_UUID, "jwt@example.com",
                List.of("METRICS", "PLAY", "CHAT", "offline_access", "default-roles-schnappy")));
        var request = bearerRequest();

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user.uuid()).isEqualTo(TEST_UUID);
        assertThat(user.email()).isEqualTo("jwt@example.com");
        // Keycloak realm-default roles are filtered; identity + roles come only
        // from the validated token, never from request headers.
        assertThat(user.permissions()).containsExactlyInAnyOrder("METRICS", "PLAY", "CHAT");
    }

    @Test
    void spoofedHeadersWithoutToken_doesNotAuthenticate() throws Exception {
        // The core of the fix: X-User-* headers are no longer trusted, so an
        // east-west caller cannot impersonate a user by setting them.
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", TEST_UUID.toString());
        request.addHeader("X-User-Email", "attacker@example.com");
        request.addHeader("X-User-Permissions", "MANAGE_USERS");

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void forgedToken_isRejected() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("bad signature"));

        filter.doFilterInternal(bearerRequest(), new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void noToken_doesNotAuthenticate() throws Exception {
        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void basicAuth_isIgnored() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void nonUuidSubject_doesNotAuthenticate() throws Exception {
        when(jwtDecoder.decode(anyString())).thenReturn(
                Jwt.withTokenValue("t").header("alg", "none").subject("not-a-uuid").build());

        filter.doFilterInternal(bearerRequest(), new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void tokenWithNoRoles_setsEmptyPermissions() throws Exception {
        when(jwtDecoder.decode(anyString())).thenReturn(
                Jwt.withTokenValue("t").header("alg", "none")
                        .subject(TEST_UUID.toString())
                        .claim("email", "noroles@example.com")
                        .build());
        var request = bearerRequest();

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user).isNotNull();
        assertThat(user.uuid()).isEqualTo(TEST_UUID);
        assertThat(user.email()).isEqualTo("noroles@example.com");
        assertThat(user.permissions()).isEmpty();
    }

    @Test
    void filterChainAlwaysProceeds() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    // --- GatewayUser ---

    @Test
    void gatewayUser_hasPermission() {
        var user = new GatewayUser(UUID.randomUUID(), "email", List.of("METRICS", "PLAY"));
        assertThat(user.hasPermission("METRICS")).isTrue();
        assertThat(user.hasPermission("PLAY")).isTrue();
        assertThat(user.hasPermission("CHAT")).isFalse();
    }

    @Test
    void gatewayUser_emptyPermissions() {
        var user = new GatewayUser(UUID.randomUUID(), "email", List.of());
        assertThat(user.hasPermission("METRICS")).isFalse();
    }

    private static MockHttpServletRequest bearerRequest() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", BEARER);
        return request;
    }

    private static Jwt jwt(UUID sub, String email, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(sub.toString())
                .claim("email", email)
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
