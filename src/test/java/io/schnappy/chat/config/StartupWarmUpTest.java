package io.schnappy.chat.config;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.schnappy.chat.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The warm-up fetches Keycloak's keys and runs the channel listing for no one; neither a Keycloak nor a database that
 * does not answer stops the start.
 */
class StartupWarmUpTest {

    @SuppressWarnings("unchecked")
    private final JWKSource<SecurityContext> keys = mock(JWKSource.class);
    private final ChatService chatService = mock(ChatService.class);
    private final StartupWarmUp warmUp = new StartupWarmUp(keys, chatService);

    @Test
    void fetchesTheKeysAndRunsTheChannelListingForNoOne() throws KeySourceException {
        when(keys.get(any(JWKSelector.class), isNull())).thenReturn(List.of());

        warmUp.run(new DefaultApplicationArguments());

        verify(keys).get(any(JWKSelector.class), isNull());
        verify(chatService).getAllChannelsWithMembership(StartupWarmUp.NO_USER);
        verifyNoMoreInteractions(chatService);
    }

    @Test
    void aKeycloakThatDoesNotAnswerDoesNotStopTheStart() throws KeySourceException {
        when(keys.get(any(JWKSelector.class), isNull())).thenThrow(new KeySourceException("connect timed out"));
        var args = new DefaultApplicationArguments();

        assertThatCode(() -> warmUp.run(args)).doesNotThrowAnyException();
        verify(chatService).getAllChannelsWithMembership(StartupWarmUp.NO_USER);
    }

    @Test
    void aDatabaseThatDoesNotAnswerDoesNotStopTheStart() throws KeySourceException {
        when(keys.get(any(JWKSelector.class), isNull())).thenReturn(List.of());
        when(chatService.getAllChannelsWithMembership(StartupWarmUp.NO_USER))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        var args = new DefaultApplicationArguments();

        assertThatCode(() -> warmUp.run(args)).doesNotThrowAnyException();
    }
}
