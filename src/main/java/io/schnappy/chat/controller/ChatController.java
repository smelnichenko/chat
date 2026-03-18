package io.schnappy.chat.controller;

import io.schnappy.chat.config.ChatProperties;
import io.schnappy.chat.dto.ChannelDto;
import io.schnappy.chat.dto.ChannelKeyBundleDto;
import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.dto.CreateChannelRequest;
import io.schnappy.chat.dto.EditMessageRequest;
import io.schnappy.chat.dto.SendMessageRequest;
import io.schnappy.chat.dto.SetChannelKeysRequest;
import io.schnappy.chat.dto.UploadKeysRequest;
import io.schnappy.chat.dto.UserKeysDto;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import io.schnappy.chat.service.ChatService;
import io.schnappy.common.security.Permission;
import io.schnappy.common.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@RequirePermission(Permission.CHAT)
public class ChatController {

    private final ChatService chatService;
    private final ChatProperties chatProperties;

    @GetMapping("/channels")
    public List<ChannelDto> getChannels(@AuthenticationPrincipal Jwt jwt) {
        return chatService.getAllChannelsWithMembership(getUserId(jwt));
    }

    @PostMapping("/channels")
    public ResponseEntity<ChannelDto> createChannel(@Valid @RequestBody CreateChannelRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        var channel = chatService.createChannel(request, getUserId(jwt));
        var dto = ChannelDto.builder()
            .id(channel.getId())
            .name(channel.getName())
            .createdAt(channel.getCreatedAt().toString())
            .memberCount(1)
            .joined(true)
            .isOwner(true)
            .encrypted(channel.isEncrypted())
            .currentKeyVersion(channel.getCurrentKeyVersion())
            .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PostMapping("/channels/{channelId}/leave")
    public ResponseEntity<Void> leaveChannel(@PathVariable Long channelId,
                                             @AuthenticationPrincipal Jwt jwt) {
        chatService.leaveChannel(channelId, getUserId(jwt));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/channels/{channelId}")
    public ResponseEntity<Void> deleteChannel(@PathVariable Long channelId,
                                              @AuthenticationPrincipal Jwt jwt) {
        chatService.deleteChannel(channelId, getUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/channels/{channelId}/messages")
    public List<ChatMessageDto> getMessages(@PathVariable Long channelId,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @AuthenticationPrincipal Jwt jwt) {
        requireMembership(channelId, jwt);
        return chatService.getMessages(channelId, Math.min(limit, 100));
    }

    @PostMapping("/channels/{channelId}/messages")
    public ResponseEntity<ChatMessageDto> sendMessage(@PathVariable Long channelId,
                                                       @Valid @RequestBody SendMessageRequest request,
                                                       @AuthenticationPrincipal Jwt jwt) {
        requireMembership(channelId, jwt);
        var message = chatService.sendMessage(channelId, request, getUserId(jwt), getUsername(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    @GetMapping("/channels/{channelId}/members")
    public List<Map<String, Object>> getMembers(@PathVariable Long channelId,
                                                           @AuthenticationPrincipal Jwt jwt) {
        requireMembership(channelId, jwt);
        return chatService.getChannelMembers(channelId);
    }

    @PostMapping("/channels/{channelId}/kick")
    public ResponseEntity<Void> kickUser(@PathVariable Long channelId,
                                         @RequestBody Map<String, Long> body,
                                         @AuthenticationPrincipal Jwt jwt) {
        Long userId = body.get("userId");
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        chatService.kickFromChannel(channelId, userId, getUserId(jwt));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/channels/{channelId}/invite")
    public ResponseEntity<Void> inviteUser(@PathVariable Long channelId,
                                           @RequestBody Map<String, Long> body,
                                           @AuthenticationPrincipal Jwt jwt) {
        Long userId = body.get("userId");
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        chatService.inviteToChannel(channelId, userId, getUserId(jwt));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> getUsers(@AuthenticationPrincipal Jwt jwt) {
        return chatService.getChatUsers(getUserId(jwt));
    }

    @PutMapping("/channels/{channelId}/messages/{messageId}")
    public ResponseEntity<Void> editMessage(@PathVariable Long channelId,
                                            @PathVariable String messageId,
                                            @Valid @RequestBody EditMessageRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        requireMembership(channelId, jwt);
        chatService.editMessage(channelId, messageId, request.content(), getUserId(jwt));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/channels/{channelId}/messages/{messageId}/edits")
    public List<ScyllaMessageRepository.EditRecord> getMessageEdits(@PathVariable Long channelId,
                                                                     @PathVariable String messageId,
                                                                     @AuthenticationPrincipal Jwt jwt) {
        requireMembership(channelId, jwt);
        return chatService.getMessageEdits(channelId, messageId);
    }

    @GetMapping("/channels/{channelId}/verify")
    public ScyllaMessageRepository.ChainVerification verifyChain(@PathVariable Long channelId,
                                                                  @AuthenticationPrincipal Jwt jwt) {
        requireMembership(channelId, jwt);
        return chatService.verifyChain(channelId);
    }

    @PostMapping("/channels/{channelId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long channelId,
                                           @AuthenticationPrincipal Jwt jwt) {
        chatService.updateLastRead(channelId, getUserId(jwt));
        return ResponseEntity.ok().build();
    }

    // --- E2E Encryption: Key Management ---

    @GetMapping("/keys")
    public ResponseEntity<UserKeysDto> getKeys(@AuthenticationPrincipal Jwt jwt) {
        requireE2e();
        var keys = chatService.getUserKeys(getUserId(jwt));
        if (keys == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(keys);
    }

    @PostMapping("/keys")
    public ResponseEntity<UserKeysDto> uploadKeys(@Valid @RequestBody UploadKeysRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        requireE2e();
        var keys = chatService.uploadUserKeys(request, getUserId(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(keys);
    }

    @PutMapping("/keys")
    public ResponseEntity<Void> updateKeys(@Valid @RequestBody UploadKeysRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        requireE2e();
        chatService.updateUserKeys(request, getUserId(jwt));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/keys/public")
    public List<Map<String, Object>> getPublicKeys(@RequestParam List<Long> userIds) {
        requireE2e();
        return chatService.getPublicKeys(userIds);
    }

    @GetMapping("/channels/{channelId}/keys")
    public ResponseEntity<List<ChannelKeyBundleDto>> getChannelKeys(
            @PathVariable Long channelId,
            @RequestParam(required = false) Integer keyVersion,
            @AuthenticationPrincipal Jwt jwt) {
        requireE2e();
        requireMembership(channelId, jwt);
        var bundles = chatService.getChannelKeyBundles(channelId, getUserId(jwt), keyVersion);
        return ResponseEntity.ok(bundles);
    }

    @PostMapping("/channels/{channelId}/keys")
    public ResponseEntity<Void> setChannelKeys(@PathVariable Long channelId,
                                                @Valid @RequestBody SetChannelKeysRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        requireE2e();
        chatService.setChannelKeys(channelId, request, getUserId(jwt));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/channels/{channelId}/keys/rotate")
    public ResponseEntity<Map<String, Integer>> rotateChannelKeys(@PathVariable Long channelId,
                                                    @Valid @RequestBody SetChannelKeysRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        requireE2e();
        int newVersion = chatService.rotateChannelKeys(channelId, request, getUserId(jwt));
        return ResponseEntity.ok(Map.of("newKeyVersion", newVersion));
    }

    private void requireE2e() {
        if (!chatProperties.e2eEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void requireMembership(Long channelId, Jwt jwt) {
        if (!chatService.isMember(channelId, getUserId(jwt))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this channel");
        }
    }

    private Long getUserId(Jwt jwt) {
        return jwt.getClaim("uid");
    }

    private String getUsername(Jwt jwt) {
        return jwt.getClaimAsString("email");
    }
}
