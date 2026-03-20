package io.schnappy.chat.service;

import io.schnappy.chat.entity.Channel;
import io.schnappy.chat.entity.ChannelMember;
import io.schnappy.chat.kafka.ChatKafkaProducer;
import io.schnappy.chat.repository.ChannelMemberRepository;
import io.schnappy.chat.repository.ChannelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemChannelServiceTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private ChannelMemberRepository memberRepository;

    @Mock
    private ChatKafkaProducer kafkaProducer;

    @Mock
    private UserCacheService userCacheService;

    @InjectMocks
    private SystemChannelService systemChannelService;

    // --- getOrCreateAdminChannel ---

    @Test
    void getOrCreateAdminChannel_existingChannel_returnsIt() {
        var existing = new Channel();
        existing.setId(5L);
        existing.setName("Admin Notifications");
        existing.setSystem(true);

        when(channelRepository.findByNameAndSystemTrue("Admin Notifications"))
                .thenReturn(Optional.of(existing));

        var result = systemChannelService.getOrCreateAdminChannel();

        assertThat(result.getId()).isEqualTo(5L);
        verify(channelRepository, never()).save(any());
    }

    @Test
    void getOrCreateAdminChannel_noExistingChannel_createsNewOne() {
        when(channelRepository.findByNameAndSystemTrue("Admin Notifications"))
                .thenReturn(Optional.empty());
        when(userCacheService.getAdminUserIds()).thenReturn(Set.of(1L, 2L));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            c.setId(10L);
            return c;
        });
        when(memberRepository.existsByChannelIdAndUserId(any(), any())).thenReturn(false);
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = systemChannelService.getOrCreateAdminChannel();

        assertThat(result.getName()).isEqualTo("Admin Notifications");
        assertThat(result.isSystem()).isTrue();
        verify(channelRepository).save(any(Channel.class));
    }

    @Test
    void getOrCreateAdminChannel_noAdmins_throwsIllegalState() {
        when(channelRepository.findByNameAndSystemTrue("Admin Notifications"))
                .thenReturn(Optional.empty());
        when(userCacheService.getAdminUserIds()).thenReturn(Set.of());

        assertThatThrownBy(() -> systemChannelService.getOrCreateAdminChannel())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No admin users found");
    }

    // --- syncAdminChannelMembers ---

    @Test
    void syncAdminChannelMembers_addsNewAdmins() {
        var channel = new Channel();
        channel.setId(5L);
        channel.setName("Admin Notifications");

        when(channelRepository.findByNameAndSystemTrue("Admin Notifications"))
                .thenReturn(Optional.of(channel));
        when(userCacheService.getAdminUserIds()).thenReturn(Set.of(1L, 2L));

        var existingMember = new ChannelMember();
        existingMember.setUserId(1L);
        existingMember.setChannelId(5L);
        when(memberRepository.findByChannelId(5L)).thenReturn(List.of(existingMember));

        // User 2 is not yet a member
        when(memberRepository.existsByChannelIdAndUserId(5L, 2L)).thenReturn(false);
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        systemChannelService.syncAdminChannelMembers();

        var captor = ArgumentCaptor.forClass(ChannelMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(2L);
    }

    @Test
    void syncAdminChannelMembers_noChannel_doesNothing() {
        when(channelRepository.findByNameAndSystemTrue("Admin Notifications"))
                .thenReturn(Optional.empty());

        systemChannelService.syncAdminChannelMembers();

        verify(memberRepository, never()).findByChannelId(any());
    }

    @Test
    void syncAdminChannelMembers_allAdminsAlreadyMembers_doesNotAddNew() {
        var channel = new Channel();
        channel.setId(5L);

        when(channelRepository.findByNameAndSystemTrue("Admin Notifications"))
                .thenReturn(Optional.of(channel));
        when(userCacheService.getAdminUserIds()).thenReturn(Set.of(1L));

        var existingMember = new ChannelMember();
        existingMember.setUserId(1L);
        existingMember.setChannelId(5L);
        when(memberRepository.findByChannelId(5L)).thenReturn(List.of(existingMember));

        systemChannelService.syncAdminChannelMembers();

        verify(memberRepository, never()).save(any());
    }

    // --- postSystemMessage ---

    @Test
    void postSystemMessage_sendsViaKafkaProducer() {
        systemChannelService.postSystemMessage(5L, "Hello system", "meta123");

        var captor = ArgumentCaptor.forClass(io.schnappy.chat.dto.ChatMessageDto.class);
        verify(kafkaProducer).sendMessage(captor.capture());

        var msg = captor.getValue();
        assertThat(msg.getChannelId()).isEqualTo(5L);
        assertThat(msg.getContent()).isEqualTo("Hello system");
        assertThat(msg.getMetadata()).isEqualTo("meta123");
        assertThat(msg.getUserId()).isZero();
        assertThat(msg.getUsername()).isEqualTo("System");
        assertThat(msg.getMessageType()).isEqualTo("SYSTEM");
        assertThat(msg.getMessageId()).isNotNull();
        assertThat(msg.getCreatedAt()).isNotNull();
    }

    @Test
    void postSystemMessage_nullMetadata_sendsWithNullMetadata() {
        systemChannelService.postSystemMessage(5L, "Test", null);

        var captor = ArgumentCaptor.forClass(io.schnappy.chat.dto.ChatMessageDto.class);
        verify(kafkaProducer).sendMessage(captor.capture());
        assertThat(captor.getValue().getMetadata()).isNull();
    }
}
