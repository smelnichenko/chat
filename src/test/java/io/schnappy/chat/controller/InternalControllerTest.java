package io.schnappy.chat.controller;

import io.schnappy.chat.repository.ChannelMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalControllerTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private ChannelMemberRepository memberRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalController(memberRepository)).build();
    }

    @Test
    void membership_userIsMember_returns200() throws Exception {
        when(memberRepository.existsByChannelIdAndUserUuid(7L, USER_UUID)).thenReturn(true);

        mockMvc.perform(get("/internal/membership")
                        .param("user", USER_UUID.toString())
                        .param("channel", "chat:room:7"))
                .andExpect(status().isOk());
    }

    @Test
    void membership_userNotMember_returns404() throws Exception {
        when(memberRepository.existsByChannelIdAndUserUuid(7L, USER_UUID)).thenReturn(false);

        mockMvc.perform(get("/internal/membership")
                        .param("user", USER_UUID.toString())
                        .param("channel", "chat:room:7"))
                .andExpect(status().isNotFound());
    }

    @Test
    void membership_invalidUserUuid_returns404WithoutLookup() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", "not-a-uuid")
                        .param("channel", "chat:room:7"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(memberRepository);
    }

    @Test
    void membership_wrongChannelSegmentCount_returns404() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", USER_UUID.toString())
                        .param("channel", "chat:room"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(memberRepository);
    }

    @Test
    void membership_wrongChannelPrefix_returns404() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", USER_UUID.toString())
                        .param("channel", "notification:room:7"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(memberRepository);
    }

    @Test
    void membership_wrongChannelMiddleSegment_returns404() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", USER_UUID.toString())
                        .param("channel", "chat:dm:7"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(memberRepository);
    }

    @Test
    void membership_nonNumericChannelId_returns404() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", USER_UUID.toString())
                        .param("channel", "chat:room:abc"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(memberRepository);
    }
}
