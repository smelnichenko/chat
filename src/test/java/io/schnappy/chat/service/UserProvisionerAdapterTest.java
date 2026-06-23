package io.schnappy.chat.service;

import com.sun.net.httpserver.HttpServer;
import io.schnappy.chat.entity.ChatUser;
import io.schnappy.chat.repository.ChatUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProvisionerAdapterTest {

    private static final String UUID_STRING = "550e8400-e29b-41d4-a716-446655440000";
    private static final UUID USER_UUID = UUID.fromString(UUID_STRING);
    private static final String BEARER = "Bearer header.payload.sig";

    @Mock
    private ChatUserRepository chatUserRepository;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void provisionUser_alreadyExists_returnsWithoutCallingAdmin() {
        var existing = new ChatUser();
        existing.setUuid(USER_UUID);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(existing));
        AtomicReference<String> hit = stubAdmin(200);
        bearerRequest();

        adapter().provisionUser(UUID_STRING, "alice@example.com", List.of("CHAT"));

        assertThat(hit.get()).isNull();
    }

    @Test
    void provisionUser_noBearerToken_skipsProvisioning() {
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());
        AtomicReference<String> hit = stubAdmin(200);
        // No RequestContextHolder bound → currentBearerToken() returns null.

        adapter().provisionUser(UUID_STRING, "alice@example.com", List.of("CHAT"));

        assertThat(hit.get()).isNull();
    }

    @Test
    void provisionUser_requestWithoutAuthHeader_skipsProvisioning() {
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());
        AtomicReference<String> hit = stubAdmin(200);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        adapter().provisionUser(UUID_STRING, "alice@example.com", List.of("CHAT"));

        assertThat(hit.get()).isNull();
    }

    @Test
    void provisionUser_newUserWithToken_relaysBearerToAdmin() {
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());
        AtomicReference<String> hit = stubAdmin(200);
        bearerRequest();

        adapter().provisionUser(UUID_STRING, "alice@example.com", List.of("CHAT"));

        // The validated Bearer token is relayed verbatim to the admin endpoint.
        assertThat(hit.get()).isEqualTo(BEARER);
    }

    @Test
    void provisionUser_adminReturnsError_swallowsException() {
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());
        stubAdmin(500);
        bearerRequest();

        // A 5xx from admin makes RestClient throw; the adapter must not propagate it.
        assertThatCode(() -> adapter().provisionUser(UUID_STRING, "alice@example.com", List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void provisionUser_malformedUuid_swallowsException() {
        // UUID.fromString throws inside the try → caught and logged, never propagated.
        assertThatCode(() -> adapter().provisionUser("not-a-uuid", "alice@example.com", List.of()))
                .doesNotThrowAnyException();
        verify(chatUserRepository, never()).findByUuid(USER_UUID);
    }

    private UserProvisionerAdapter adapter() {
        return new UserProvisionerAdapter(chatUserRepository, baseUrl);
    }

    /** Records the Authorization header seen by the admin stub, replies with the given status. */
    private AtomicReference<String> stubAdmin(int status) {
        var seenAuth = new AtomicReference<String>();
        server.createContext("/api/auth/ensure-user", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            byte[] body = new byte[0];
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().close();
        });
        return seenAuth;
    }

    private static void bearerRequest() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, BEARER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
