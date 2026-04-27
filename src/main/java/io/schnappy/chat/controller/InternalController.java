package io.schnappy.chat.controller;

import io.schnappy.chat.repository.ChannelMemberRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal endpoints consumed by sibling services over mTLS. Auth is
 * enforced by Istio mesh-level DENY policies (see schnappy-mesh chart);
 * Spring Security explicitly bypasses /internal/** so the cluster
 * principal — not a Keycloak JWT — is the authority.
 *
 * Membership lookup: returns 200 if the caller-supplied user is in the
 * given chat channel, 404 otherwise. Used by the admin service when
 * minting Centrifugo channel-subscription tokens.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final ChannelMemberRepository memberRepository;

    public InternalController(ChannelMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * @param user    Keycloak user UUID
     * @param channel Centrifugo channel string, expected shape {@code chat:room:<id>}
     */
    @GetMapping("/membership")
    public ResponseEntity<Void> membership(@RequestParam("user") String user,
                                            @RequestParam("channel") String channel) {
        UUID userUuid;
        try {
            userUuid = UUID.fromString(user);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        // Channel must be `chat:room:<numericChannelId>`. Anything else → not a member.
        String[] parts = channel.split(":");
        if (parts.length != 3 || !"chat".equals(parts[0]) || !"room".equals(parts[1])) {
            return ResponseEntity.notFound().build();
        }
        long channelId;
        try {
            channelId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return ResponseEntity.notFound().build();
        }

        if (memberRepository.existsByChannelIdAndUserUuid(channelId, userUuid)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}
