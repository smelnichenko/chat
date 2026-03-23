package io.schnappy.chat.security;

import io.schnappy.chat.service.UserCacheService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GatewayAuthFilterTest {

    private static final String TEST_UUID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private UserCacheService userCacheService;

    @Mock
    private FilterChain filterChain;

    private GatewayAuthFilter gatewayAuthFilter;

    @BeforeEach
    void setUp() {
        // Resolver that returns 42L for the test UUID, null otherwise
        UserIdResolver resolver = uuid -> TEST_UUID.equals(uuid) ? 42L : null;
        gatewayAuthFilter = new GatewayAuthFilter(userCacheService, resolver);
        SecurityContextHolder.clearContext();
    }

    @Test
    void validHeaders_populatesSecurityContextAndCachesUser() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", TEST_UUID);
        request.addHeader("X-User-Email", "alice@example.com");
        request.addHeader("X-User-Permissions", "CHAT,METRICS");
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();

        var user = (GatewayUser) auth.getPrincipal();
        assertThat(user.uuid()).isEqualTo(TEST_UUID);
        assertThat(user.userId()).isEqualTo(42L);
        assertThat(user.email()).isEqualTo("alice@example.com");
        assertThat(user.permissions()).containsExactlyInAnyOrder("CHAT", "METRICS");

        var attrUser = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(attrUser).isEqualTo(user);

        verify(userCacheService).cacheUser(42L, "alice@example.com", true);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void noUserUuidHeader_doesNotAuthenticate() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userCacheService, never()).cacheUser(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void blankUserUuidHeader_doesNotAuthenticate() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", "   ");
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void userNotInLocalTable_setsNullUserIdAndSkipsCache() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", "00000000-0000-0000-0000-000000000099");
        request.addHeader("X-User-Email", "alice@example.com");
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        var user = (GatewayUser) auth.getPrincipal();
        assertThat(user.uuid()).isEqualTo("00000000-0000-0000-0000-000000000099");
        assertThat(user.userId()).isNull();

        verify(userCacheService, never()).cacheUser(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void emptyPermissions_yieldsEmptyList() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", TEST_UUID);
        request.addHeader("X-User-Email", "bob@example.com");
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        var user = (GatewayUser) auth.getPrincipal();
        assertThat(user.permissions()).isEmpty();
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void singlePermission_populatedCorrectly() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", TEST_UUID);
        request.addHeader("X-User-Email", "carol@example.com");
        request.addHeader("X-User-Permissions", "CHAT");
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        var user = (GatewayUser) auth.getPrincipal();
        assertThat(user.permissions()).containsExactly("CHAT");
        assertThat(auth.getAuthorities()).hasSize(1);
        assertThat(auth.getAuthorities().iterator().next().getAuthority()).isEqualTo("CHAT");
    }

    @Test
    void filterChainAlwaysInvokedEvenWithoutAuth() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void gatewayUserHasPermission_returnsTrueForPresentPermission() {
        var user = new GatewayUser("uuid", "test@test.com", java.util.List.of("CHAT", "METRICS"), 1L);
        assertThat(user.hasPermission("CHAT")).isTrue();
        assertThat(user.hasPermission("METRICS")).isTrue();
        assertThat(user.hasPermission("PLAY")).isFalse();
    }
}
