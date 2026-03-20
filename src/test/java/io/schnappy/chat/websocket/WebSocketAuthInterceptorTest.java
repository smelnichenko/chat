package io.schnappy.chat.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WebSocketAuthInterceptorTest {

    private final WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor();

    @Test
    void beforeHandshake_validHeaders_populatesAttributesAndReturnsTrue() {
        var httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "42");
        httpRequest.addHeader("X-User-UUID", "550e8400-e29b-41d4-a716-446655440000");
        httpRequest.addHeader("X-User-Email", "alice@example.com");

        var request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isTrue();
        assertThat(attributes)
                .containsEntry("userId", 42L)
                .containsEntry("userUuid", UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
                .containsEntry("username", "alice@example.com");
    }

    @Test
    void beforeHandshake_noUuidHeader_skipsUuidAttribute() {
        var httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "42");
        httpRequest.addHeader("X-User-Email", "alice@example.com");

        var request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isTrue();
        assertThat(attributes)
                .containsEntry("userId", 42L)
                .doesNotContainKey("userUuid");
    }

    @Test
    void beforeHandshake_noUserIdHeader_returnsFalse() {
        var httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-Email", "alice@example.com");

        var request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    void beforeHandshake_blankUserIdHeader_returnsFalse() {
        var httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "   ");

        var request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isFalse();
    }

    @Test
    void beforeHandshake_malformedUserId_returnsFalse() {
        var httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "not-a-number");

        var request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isFalse();
    }

    @Test
    void beforeHandshake_invalidUuid_returnsFalse() {
        var httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "42");
        httpRequest.addHeader("X-User-UUID", "not-a-uuid");

        var request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isFalse();
    }

    @Test
    void beforeHandshake_nonServletRequest_returnsFalse() {
        var request = mock(ServerHttpRequest.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isFalse();
    }

    @Test
    void afterHandshake_doesNotThrow() {
        var request = mock(ServerHttpRequest.class);
        var response = mock(ServerHttpResponse.class);
        var handler = mock(WebSocketHandler.class);

        // afterHandshake is a no-op; verify it completes without exception
        interceptor.afterHandshake(request, response, handler, null);

        // No interactions expected — method is intentionally empty
        verifyNoInteractions(request, response, handler);
    }
}
