package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CategoryRequestDto {

    @NotBlank(message = "Tên danh mục không được để trống")
    private String name;

}
