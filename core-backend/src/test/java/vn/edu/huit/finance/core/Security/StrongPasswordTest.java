package vn.edu.huit.finance.core.Security;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The password policy of the registration, change-password and OTP reset flows, including its exact boundaries. */
class StrongPasswordTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String BLANK_MESSAGE = "Mật khẩu không được để trống";
    private static final String LENGTH_MESSAGE = "Mật khẩu phải từ 8 đến 72 ký tự";
    private static final String LETTER_AND_DIGIT_MESSAGE = "Mật khẩu phải chứa ít nhất một chữ cái và một chữ số";

    private static class Candidate {
        @StrongPassword
        private final String password;

        Candidate(String password) {
            this.password = password;
        }
    }

    private static Set<String> violationsOf(String password) {
        return VALIDATOR.validate(new Candidate(password)).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    static Stream<String> acceptedPasswords() {
        return Stream.of(
                "abcdefg1",                                          // shortest allowed: 8 characters
                "Passw0rd!",
                "1234567a",                                          // the digit may come first
                "Mật-khẩu-mạnh-2026",                                // non-ASCII letters count as letters
                "a1" + "b".repeat(StrongPassword.MAX_LENGTH - 2));   // longest allowed: 72 characters
    }

    static Stream<Arguments> passwordsBreakingExactlyOneRule() {
        return Stream.of(
                Arguments.of("abcdef1", LENGTH_MESSAGE),                                          // 7 characters
                Arguments.of("a1" + "b".repeat(StrongPassword.MAX_LENGTH - 1), LENGTH_MESSAGE),   // 73 characters
                Arguments.of("abcdefgh", LETTER_AND_DIGIT_MESSAGE),                               // no digit
                Arguments.of("12345678", LETTER_AND_DIGIT_MESSAGE),                               // no letter
                Arguments.of("!@#$%^&*()", LETTER_AND_DIGIT_MESSAGE));                            // symbols only
    }

    @ParameterizedTest
    @MethodSource("acceptedPasswords")
    void acceptsPasswordsThatMeetThePolicy(String password) {
        assertTrue(violationsOf(password).isEmpty(), "unexpected violations: " + violationsOf(password));
    }

    @ParameterizedTest
    @MethodSource("passwordsBreakingExactlyOneRule")
    void reportsOnlyTheRuleThatIsBroken(String password, String expectedMessage) {
        assertEquals(Set.of(expectedMessage), violationsOf(password));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "        "})
    void rejectsBlankPasswords_whateverTheirLength(String password) {
        assertTrue(violationsOf(password).contains(BLANK_MESSAGE));
    }

    @Test
    void policyBoundariesMatchTheDocumentedRule() {
        // Guards the messages above, which spell the numbers out: change one side and this fails.
        assertEquals(8, StrongPassword.MIN_LENGTH);
        assertEquals(72, StrongPassword.MAX_LENGTH);
    }
}
