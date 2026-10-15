package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import vn.edu.huit.finance.core.Security.StrongPassword;

// Deliberately not @Data: its generated toString() would print the password.
@Getter
@Setter
public class RegisterRequestDto {

    @NotBlank(message = "Tên đăng nhập không được để trống")
    private String username;

    @StrongPassword
    private String password;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    @NotBlank(message = "Số điện thoại không được để trống")
    @Pattern(regexp = "^\\d{10}$", message = "Số điện thoại phải bao gồm đúng 10 chữ số")
    private String phone;
}
