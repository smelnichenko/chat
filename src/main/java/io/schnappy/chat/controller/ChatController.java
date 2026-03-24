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
import io.schnappy.chat.security.GatewayUser;
import io.schnappy.chat.security.Permission;
import io.schnappy.chat.security.RequirePermission;
import io.schnappy.chat.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@RequirePermission(Permission.CHAT)
public class ChatController {

    private final ChatService chatService;
    private final ChatProperties chatProperties;

    @GetMapping("/channels")
    public List<ChannelDto> getChannels(@RequestAttribute("gatewayUser") GatewayUser user) {
        return chatService.getAllChannelsWithMembership(user.uuid());
    }

    @PostMapping("/channels")
    public ResponseEntity<ChannelDto> createChannel(@Valid @RequestBody CreateChannelRequest request,
                                                     @RequestAttribute("gatewayUser") GatewayUser user) {
        var channel = chatService.createChannel(request, user.uuid());
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
                                             @RequestAttribute("gatewayUser") GatewayUser user) {
        chatService.leaveChannel(channelId, user.uuid());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/channels/{channelId}")
    public ResponseEntity<Void> deleteChannel(@PathVariable Long channelId,
                                              @RequestAttribute("gatewayUser") GatewayUser user) {
        chatService.deleteChannel(channelId, user.uuid());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/channels/{channelId}/messages")
    public List<ChatMessageDto> getMessages(@PathVariable Long channelId,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @RequestAttribute("gatewayUser") GatewayUser user) {
        requireMembership(channelId, user);
        return chatService.getMessages(channelId, Math.min(limit, 100));
    }

    @PostMapping("/channels/{channelId}/messages")
    public ResponseEntity<ChatMessageDto> sendMessage(@PathVariable Long channelId,
                                                       @Valid @RequestBody SendMessageRequest request,
                                                       @RequestAttribute("gatewayUser") GatewayUser user) {
        requireMembership(channelId, user);
        var message = chatService.sendMessage(channelId, request, user.uuid(), user.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    @GetMapping("/channels/{channelId}/members")
    public List<Map<String, Object>> getMembers(@PathVariable Long channelId,
                                                           @RequestAttribute("gatewayUser") GatewayUser user) {
        requireMembership(channelId, user);
        return chatService.getChannelMembers(channelId);
    }

    @PostMapping("/channels/{channelId}/kick")
    public ResponseEntity<Void> kickUser(@PathVariable Long channelId,
                                         @RequestBody Map<String, String> body,
                                         @RequestAttribute("gatewayUser") GatewayUser user) {
        String userUuidStr = body.get("userUuid");
        if (userUuidStr == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userUuid is required");
        }
        UUID targetUuid = UUID.fromString(userUuidStr);
        chatService.kickFromChannel(channelId, targetUuid, user.uuid());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/channels/{channelId}/invite")
    public ResponseEntity<Void> inviteUser(@PathVariable Long channelId,
                                           @RequestBody Map<String, String> body,
                                           @RequestAttribute("gatewayUser") GatewayUser user) {
        String userUuidStr = body.get("userUuid");
        if (userUuidStr == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userUuid is required");
        }
        UUID targetUuid = UUID.fromString(userUuidStr);
        chatService.inviteToChannel(channelId, targetUuid, user.uuid());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> getUsers(@RequestAttribute("gatewayUser") GatewayUser user) {
        return chatService.getChatUsers(user.uuid());
    }

    @PutMapping("/channels/{channelId}/messages/{messageId}")
    public ResponseEntity<Void> editMessage(@PathVariable Long channelId,
                                            @PathVariable String messageId,
                                            @Valid @RequestBody EditMessageRequest request,
                                            @RequestAttribute("gatewayUser") GatewayUser user) {
        requireMembership(channelId, user);
        chatService.editMessage(channelId, messageId, request.content(), user.uuid());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/channels/{channelId}/messages/{messageId}/edits")
    public List<ScyllaMessageRepository.EditRecord> getMessageEdits(@PathVariable Long channelId,
                                                                     @PathVariable String messageId,
                                                                     @RequestAttribute("gatewayUser") GatewayUser user) {
        requireMembership(channelId, user);
        return chatService.getMessageEdits(channelId, messageId);
    }

    @GetMapping("/channels/{channelId}/verify")
    public ScyllaMessageRepository.ChainVerification verifyChain(@PathVariable Long channelId,
                                                                  @RequestAttribute("gatewayUser") GatewayUser user) {
        requireMembership(channelId, user);
        return chatService.verifyChain(channelId);
    }

    @PostMapping("/channels/{channelId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long channelId,
                                           @RequestAttribute("gatewayUser") GatewayUser user) {
        chatService.updateLastRead(channelId, user.uuid());
        return ResponseEntity.ok().build();
    }

    // --- E2E Encryption: Key Management ---

    @GetMapping("/keys")
    public ResponseEntity<UserKeysDto> getKeys(@RequestAttribute("gatewayUser") GatewayUser user) {
        requireE2e();
        var keys = chatService.getUserKeys(user.uuid());
        if (keys == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(keys);
    }

    @PostMapping("/keys")
    public ResponseEntity<UserKeysDto> uploadKeys(@Valid @RequestBody UploadKeysRequest request,
                                                   @RequestAttribute("gatewayUser") GatewayUser user) {
        requireE2e();
        var keys = chatService.uploadUserKeys(request, user.uuid());
        return ResponseEntity.status(HttpStatus.CREATED).body(keys);
    }

    @PutMapping("/keys")
    public ResponseEntity<Void> updateKeys(@Valid @RequestBody UploadKeysRequest request,
                                            @RequestAttribute("gatewayUser") GatewayUser user) {
        requireE2e();
        chatService.updateUserKeys(request, user.uuid());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/keys/public")
    public List<Map<String, Object>> getPublicKeys(@RequestParam List<UUID> userUuids) {
        requireE2e();
        return chatService.getPublicKeys(userUuids);
    }

    @GetMapping("/channels/{channelId}/keys")
    public ResponseEntity<List<ChannelKeyBundleDto>> getChannelKeys(
            @PathVariable Long channelId,
            @RequestParam(required = false) Integer keyVersion,
            @RequestAttribute("gatewayUser") GatewayUser user) {
        requireE2e();
        requireMembership(channelId, user);
        var bundles = chatService.getChannelKeyBundles(channelId, user.uuid(), keyVersion);
        return ResponseEntity.ok(bundles);
    }

    @PostMapping("/channels/{channelId}/keys")
    public ResponseEntity<Void> setChannelKeys(@PathVariable Long channelId,
                                                @Valid @RequestBody SetChannelKeysRequest request,
                                                @RequestAttribute("gatewayUser") GatewayUser user) {
        requireE2e();
        chatService.setChannelKeys(channelId, request, user.uuid());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/channels/{channelId}/keys/rotate")
    public ResponseEntity<Map<String, Integer>> rotateChannelKeys(@PathVariable Long channelId,
                                                    @Valid @RequestBody SetChannelKeysRequest request,
                                                    @RequestAttribute("gatewayUser") GatewayUser user) {
        requireE2e();
        int newVersion = chatService.rotateChannelKeys(channelId, request, user.uuid());
        return ResponseEntity.ok(Map.of("newKeyVersion", newVersion));
    }

    private void requireE2e() {
        if (!chatProperties.e2eEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void requireMembership(Long channelId, GatewayUser user) {
        if (!chatService.isMember(channelId, user.uuid())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this channel");
        }
    }
}
