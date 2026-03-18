package io.schnappy.chat.controller;

import io.schnappy.chat.dto.SendMessageRequest;
import io.schnappy.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatService chatService;

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload Map<String, Object> payload,
                           SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs == null) return;

        Long userId = (Long) attrs.get("userId");
        String username = (String) attrs.get("username");
        if (userId == null) return;

        Object channelIdObj = payload.get("channelId");
        String content = (String) payload.get("content");
        if (channelIdObj == null || content == null || content.isBlank() || content.length() > 4000) return;

        Long channelId = Long.valueOf(channelIdObj.toString());
        String parentMessageId = payload.containsKey("parentMessageId")
            ? (String) payload.get("parentMessageId") : null;

        if (!chatService.isMember(channelId, userId)) {
            log.warn("User {} tried to send to channel {} without membership", userId, channelId);
            return;
        }

        Integer keyVersion = payload.containsKey("keyVersion")
            ? Integer.valueOf(payload.get("keyVersion").toString()) : null;
        chatService.sendMessage(channelId, new SendMessageRequest(content, parentMessageId, keyVersion), userId, username);
    }

    @MessageMapping("/chat.read")
    public void markAsRead(@Payload Map<String, Object> payload,
                          SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs == null) return;

        Long userId = (Long) attrs.get("userId");
        Object channelIdObj = payload.get("channelId");
        if (userId == null || channelIdObj == null) return;
        Long channelId = Long.valueOf(channelIdObj.toString());
        chatService.updateLastRead(channelId, userId);
    }
}
