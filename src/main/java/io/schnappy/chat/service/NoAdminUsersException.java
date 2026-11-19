package io.schnappy.chat.service;

/**
 * No admin user yet, so no admin channel to make - nothing a retry changes until an admin exists. An IllegalStateException,
 * as it was: the API answers it as before (GlobalExceptionHandler).
 */
public class NoAdminUsersException extends IllegalStateException {
    public NoAdminUsersException() {
        super("No admin users found to create system channel");
    }
}
