package vn.edu.huit.finance.core.Security;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Policy for every flow that sets a password (registration, change password, OTP reset): not blank,
 * {@link #MIN_LENGTH} to {@link #MAX_LENGTH} characters, at least one letter and one digit. It is composed of standard
 * constraints, so each broken rule reports its own message. Never put it on a password that is only checked, such as
 * the old password: existing accounts may hold passwords that were valid under an older policy.
 * The mobile app mirrors these rules in mobile-app/lib/core/utils/password_policy.dart: change both together.
 */
@NotBlank(message = "Mật khẩu không được để trống")
@Size(min = StrongPassword.MIN_LENGTH, max = StrongPassword.MAX_LENGTH,
        message = "Mật khẩu phải từ {min} đến {max} ký tự")
@Pattern(regexp = StrongPassword.LETTER_AND_DIGIT_REGEX,
        message = "Mật khẩu phải chứa ít nhất một chữ cái và một chữ số")
@Documented
@Constraint(validatedBy = {})
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface StrongPassword {

    int MIN_LENGTH = 8;
    // BCrypt only uses the first 72 bytes of its input.
    int MAX_LENGTH = 72;
    String LETTER_AND_DIGIT_REGEX = "^(?=.*\\p{L})(?=.*\\d).+$";

    String message() default "Mật khẩu không hợp lệ";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
