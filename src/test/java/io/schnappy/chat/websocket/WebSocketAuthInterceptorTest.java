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
        assertThat(attributes.get("userId")).isEqualTo(42L);
        assertThat(attributes.get("userUuid")).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(attributes.get("username")).isEqualTo("alice@example.com");
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
        assertThat(attributes.get("userId")).isEqualTo(42L);
        assertThat(attributes).doesNotContainKey("userUuid");
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
    void afterHandshake_doesNothing() {
        // Just verify it doesn't throw
        interceptor.afterHandshake(mock(ServerHttpRequest.class), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), null);
    }
}
