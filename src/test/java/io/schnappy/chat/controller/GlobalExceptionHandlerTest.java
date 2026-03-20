package io.schnappy.chat.controller;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidation_returnsBadRequest() {
        var ex = mock(MethodArgumentNotValidException.class);

        var response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Validation failed");
    }

    @Test
    void handleResponseStatus_returnsMatchingStatus() {
        var ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");

        var response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Access denied");
    }

    @Test
    void handleResponseStatus_nullReason_returnsGenericError() {
        var ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

        var response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Error");
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        var ex = new IllegalArgumentException("Invalid input");

        var response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid input");
    }

    @Test
    void handleIllegalState_returnsBadRequest() {
        var ex = new IllegalStateException("Bad state");

        var response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Bad state");
    }

    @Test
    void handleEntityNotFound_returns404() {
        var ex = new EntityNotFoundException("Channel not found");

        var response = handler.handleEntityNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Channel not found");
    }

    @Test
    void handleNoResourceFound_returns404() {
        var ex = mock(NoResourceFoundException.class);

        var response = handler.handleNoResourceFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Not found");
    }

    @Test
    void handleUnexpectedException_returns500_withGenericMessage() {
        var ex = new RuntimeException("Something broke internally");

        var response = handler.handleUnexpectedException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "Internal server error");
        // Ensure the actual message is NOT leaked
        assertThat(response.getBody().get("error")).doesNotContain("broke");
    }
}
