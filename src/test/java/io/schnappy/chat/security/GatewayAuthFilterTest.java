package io.schnappy.chat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GatewayAuthFilterTest {

    private static final String TEST_UUID = "550e8400-e29b-41d4-a716-446655440000";

    // JWT with sub=TEST_UUID, email=jwt@example.com, roles=[METRICS, PLAY, CHAT, offline_access, default-roles-schnappy]
    private static final String TEST_JWT = buildJwt(TEST_UUID, "jwt@example.com",
            "METRICS", "PLAY", "CHAT", "offline_access", "default-roles-schnappy");

    @Mock
    private FilterChain filterChain;

    private GatewayAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GatewayAuthFilter((uuid, email, roles) -> {});
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- Gateway header tests ---

    @Test
    void withValidHeaders_populatesSecurityContext() throws ServletException, IOException {
        var request = requestWithHeaders(TEST_UUID, "user@example.com", "METRICS,PLAY");

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user.uuid()).isEqualTo(UUID.fromString(TEST_UUID));
        assertThat(user.email()).isEqualTo("user@example.com");
        assertThat(user.permissions()).containsExactly("METRICS", "PLAY");
    }

    @ParameterizedTest(name = "unauthenticated when {0}")
    @MethodSource("unauthenticatedRequests")
    void doesNotAuthenticate(String scenario, MockHttpServletRequest request) throws ServletException, IOException {
        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> unauthenticatedRequests() {
        var noHeaders = new MockHttpServletRequest();

        var blankUuid = new MockHttpServletRequest();
        blankUuid.addHeader("X-User-UUID", "   ");

        var invalidUuid = new MockHttpServletRequest();
        invalidUuid.addHeader("X-User-UUID", "not-a-uuid");

        var basicAuth = new MockHttpServletRequest();
        basicAuth.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        var malformedJwt = new MockHttpServletRequest();
        malformedJwt.addHeader("Authorization", "Bearer not.a.jwt");

        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("no headers", noHeaders),
                org.junit.jupiter.params.provider.Arguments.of("blank UUID", blankUuid),
                org.junit.jupiter.params.provider.Arguments.of("invalid UUID", invalidUuid),
                org.junit.jupiter.params.provider.Arguments.of("Basic auth", basicAuth),
                org.junit.jupiter.params.provider.Arguments.of("malformed JWT", malformedJwt)
        );
    }

    @Test
    void withNoPermissionsHeader_extractsFromJwt() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", TEST_UUID);
        request.addHeader("Authorization", "Bearer " + TEST_JWT);

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user).isNotNull();
        assertThat(user.permissions()).containsExactlyInAnyOrder("METRICS", "PLAY", "CHAT");
    }

    @Test
    void withAllPermissions_setsAllAuthorities() throws ServletException, IOException {
        var request = requestWithHeaders(TEST_UUID, "user@example.com", "METRICS,PLAY,CHAT,EMAIL,MANAGE_USERS");

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user.permissions()).containsExactlyInAnyOrder("METRICS", "PLAY", "CHAT", "EMAIL", "MANAGE_USERS");
    }

    @Test
    void filterChainAlwaysProceeds() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    // --- JWT fallback tests ---

    @Test
    void withJwtOnly_extractsUuidAndEmail() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TEST_JWT);

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user).isNotNull();
        assertThat(user.uuid()).isEqualTo(UUID.fromString(TEST_UUID));
        assertThat(user.email()).isEqualTo("jwt@example.com");
    }

    @Test
    void withJwtOnly_filtersDefaultRoles() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TEST_JWT);

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user.permissions()).containsExactlyInAnyOrder("METRICS", "PLAY", "CHAT");
        assertThat(user.permissions()).doesNotContain("offline_access", "default-roles-schnappy");
    }

    @Test
    void withJwtNoRoles_setsEmptyPermissions() throws ServletException, IOException {
        String jwt = buildJwt(TEST_UUID, "noroles@example.com");
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt);

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user).isNotNull();
        assertThat(user.permissions()).isEmpty();
    }

    @Test
    void headerUuidTakesPrecedenceOverJwt() throws ServletException, IOException {
        String differentUuid = "660e8400-e29b-41d4-a716-446655440001";
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", differentUuid);
        request.addHeader("X-User-Email", "header@example.com");
        request.addHeader("Authorization", "Bearer " + TEST_JWT);

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user.uuid()).isEqualTo(UUID.fromString(differentUuid));
        assertThat(user.email()).isEqualTo("header@example.com");
    }

    // --- GatewayUser tests ---

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

    // --- Helpers ---

    private MockHttpServletRequest requestWithHeaders(String uuid, String email, String permissions) {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", uuid);
        request.addHeader("X-User-Email", email);
        if (permissions != null) {
            request.addHeader("X-User-Permissions", permissions);
        }
        return request;
    }

    private static String buildJwt(String sub, String email, String... roles) {
        String header = base64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        StringBuilder payload = new StringBuilder();
        payload.append("{\"sub\":\"").append(sub).append("\",\"email\":\"").append(email).append("\"");
        if (roles.length > 0) {
            payload.append(",\"realm_access\":{\"roles\":[");
            for (int i = 0; i < roles.length; i++) {
                if (i > 0) payload.append(",");
                payload.append("\"").append(roles[i]).append("\"");
            }
            payload.append("]}");
        }
        payload.append("}");
        return header + "." + base64url(payload.toString()) + "." + base64url("sig");
    }

    private static String base64url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes());
    }
}
