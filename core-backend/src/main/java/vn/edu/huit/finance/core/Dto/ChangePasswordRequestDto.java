package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import vn.edu.huit.finance.core.Security.StrongPassword;

// Deliberately not @Data: its generated toString() would print both passwords.
@Getter
@Setter
public class ChangePasswordRequestDto {

    // Only compared with the stored hash, so it is deliberately not held to the new-password policy.
    @NotBlank(message = "Mật khẩu cũ không được để trống")
    private String oldPassword;

    @StrongPassword
    private String newPassword;
}
