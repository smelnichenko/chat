package io.schnappy.chat.security;

import io.schnappy.chat.service.UserCacheService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @Mock
    private UserCacheService userCacheService;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private GatewayAuthFilter gatewayAuthFilter;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validHeaders_populatesSecurityContextAndCachesUser() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-User-UUID", "uuid-abc");
        request.addHeader("X-User-Email", "alice@example.com");
        request.addHeader("X-User-Permissions", "CHAT,METRICS");
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();

        var user = (GatewayUser) auth.getPrincipal();
        assertThat(user.userId()).isEqualTo(42L);
        assertThat(user.uuid()).isEqualTo("uuid-abc");
        assertThat(user.email()).isEqualTo("alice@example.com");
        assertThat(user.permissions()).containsExactlyInAnyOrder("CHAT", "METRICS");

        var attrUser = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(attrUser).isEqualTo(user);

        verify(userCacheService).cacheUser(42L, "alice@example.com", true);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void noUserIdHeader_doesNotAuthenticate() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userCacheService, never()).cacheUser(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void blankUserIdHeader_doesNotAuthenticate() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "   ");
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void malformedUserIdHeader_treatsAsUnauthenticated() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "not-a-number");
        var response = new MockHttpServletResponse();

        gatewayAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userCacheService, never()).cacheUser(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void emptyPermissions_yieldsEmptyList() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "7");
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
        request.addHeader("X-User-ID", "5");
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
        var user = new GatewayUser(1L, "uuid", "test@test.com", java.util.List.of("CHAT", "METRICS"));
        assertThat(user.hasPermission("CHAT")).isTrue();
        assertThat(user.hasPermission("METRICS")).isTrue();
        assertThat(user.hasPermission("PLAY")).isFalse();
    }
}
