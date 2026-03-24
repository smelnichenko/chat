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

    private static final String TEST_UUID = "550e8400-e29b-41d4-a716-446655440000";

    private final WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor();

    @Test
    void beforeHandshake_validHeaders_populatesAttributesAndReturnsTrue() {
        var httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-UUID", TEST_UUID);
        httpRequest.addHeader("X-User-Email", "alice@example.com");

        var request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isTrue();
        assertThat(attributes)
                .containsEntry("userUuid", UUID.fromString(TEST_UUID))
                .containsEntry("username", "alice@example.com");
    }

    @Test
    void beforeHandshake_noUuidHeader_returnsFalse() {
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
    void beforeHandshake_blankUuidHeader_returnsFalse() {
        var httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-UUID", "   ");

        var request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(result).isFalse();
    }

    @Test
    void beforeHandshake_malformedUuid_returnsFalse() {
        var httpRequest = new MockHttpServletRequest();
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

        interceptor.afterHandshake(request, response, handler, null);

        verifyNoInteractions(request, response, handler);
    }
}
