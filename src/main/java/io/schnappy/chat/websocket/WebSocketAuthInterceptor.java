package io.schnappy.chat.websocket;

import io.schnappy.chat.security.UserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * WebSocket handshake interceptor that reads X-User-* headers set by the API gateway.
 * The gateway validates JWT and forwards user identity as headers on the upgrade request.
 * Primary identifier is X-User-UUID; Long userId is resolved via {@link UserIdResolver}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final UserIdResolver userIdResolver;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            String userUuidHeader = httpRequest.getHeader("X-User-UUID");
            if (userUuidHeader != null && !userUuidHeader.isBlank()) {
                try {
                    UUID userUuid = UUID.fromString(userUuidHeader);
                    String email = httpRequest.getHeader("X-User-Email");

                    Long userId = userIdResolver.resolve(userUuidHeader);

                    if (userId != null) {
                        attributes.put("userId", userId);
                    }
                    attributes.put("userUuid", userUuid);
                    attributes.put("username", email);
                    return true;
                } catch (IllegalArgumentException _) {
                    log.debug("WebSocket auth failed for X-User-UUID: {}", userUuidHeader);
                }
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No post-handshake processing needed
    }
}
