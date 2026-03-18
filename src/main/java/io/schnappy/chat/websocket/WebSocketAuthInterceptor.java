package io.schnappy.chat.websocket;

import jakarta.servlet.http.HttpServletRequest;
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
 */
@Slf4j
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            String userIdHeader = httpRequest.getHeader("X-User-ID");
            if (userIdHeader != null && !userIdHeader.isBlank()) {
                try {
                    Long userId = Long.parseLong(userIdHeader);
                    String userUuid = httpRequest.getHeader("X-User-UUID");
                    String email = httpRequest.getHeader("X-User-Email");
                    attributes.put("userId", userId);
                    if (userUuid != null) {
                        attributes.put("userUuid", UUID.fromString(userUuid));
                    }
                    attributes.put("username", email);
                    return true;
                } catch (Exception e) {
                    log.debug("WebSocket auth failed: {}", e.getMessage());
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
