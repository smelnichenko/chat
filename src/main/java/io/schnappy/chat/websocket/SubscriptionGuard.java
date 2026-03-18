package io.schnappy.chat.websocket;

import io.schnappy.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionGuard {

    private static final Pattern CHANNEL_TOPIC = Pattern.compile("^/topic/channel\\.(\\d+)$");

    private final ChatService chatService;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        Map<String, Object> attrs = accessor.getSessionAttributes();
        Long userId = attrs != null ? (Long) attrs.get("userId") : null;

        Matcher channelMatcher = CHANNEL_TOPIC.matcher(destination);
        if (channelMatcher.matches()) {
            Long channelId = Long.parseLong(channelMatcher.group(1));
            if (userId == null || !chatService.isMember(channelId, userId)) {
                log.warn("Rejected subscription to channel {} by user {}", channelId, userId);
                throw new MessageDeliveryException("Not a member of channel " + channelId);
            }
        }
    }
}
