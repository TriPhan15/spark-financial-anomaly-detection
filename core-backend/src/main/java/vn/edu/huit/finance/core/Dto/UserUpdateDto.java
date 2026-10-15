package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.Email;
import lombok.Data;

@Data
public class UserUpdateDto {
    private String username;

    private String phone;

    @Email(message = "Email không hợp lệ")
    private String email;

    private String role;
}