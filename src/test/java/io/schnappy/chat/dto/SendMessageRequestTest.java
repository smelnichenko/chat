package io.schnappy.chat.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reply's parent is a message id (a uuid) - anything else is a 400 at the API, not a record chat-persistence can
 * never store (the .DLT and its alert, by any channel member).
 */
class SendMessageRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void aReplyToAMessageId_valid() {
        assertThat(validator.validate(new SendMessageRequest("hi", UUID.randomUUID().toString(), null))).isEmpty();
    }

    @Test
    void noParent_valid() {
        assertThat(validator.validate(new SendMessageRequest("hi", null, null))).isEmpty();
    }

    @Test
    void aParentThatIsNoMessageId_refused() {
        for (String parent : new String[]{"x", "", " ", "not-a-uuid-0000-0000-000000000000"}) {
            Set<ConstraintViolation<SendMessageRequest>> violations =
                    validator.validate(new SendMessageRequest("hi", parent, null));
            assertThat(violations).as("parent \"%s\"", parent)
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("parentMessageId");
        }
    }
}
