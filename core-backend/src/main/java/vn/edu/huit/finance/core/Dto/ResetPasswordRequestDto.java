package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import vn.edu.huit.finance.core.Security.StrongPassword;

// Deliberately not @Data: its generated toString() would print the OTP and the new password.
@Getter
@Setter
public class ResetPasswordRequestDto {

    @NotBlank(message = "Email không được để trống")
    private String email;

    @NotBlank(message = "Mã OTP không được để trống")
    private String otp;

    @StrongPassword
    private String newPassword;
}
