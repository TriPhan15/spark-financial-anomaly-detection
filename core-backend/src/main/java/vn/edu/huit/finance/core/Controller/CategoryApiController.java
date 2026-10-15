package vn.edu.huit.finance.core.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.huit.finance.core.Dto.CategoryResponseDto;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Repository.CategoryRepository;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/categories")
public class CategoryApiController {

    @Autowired
    private CategoryRepository categoryRepo;

    @GetMapping
    public ResponseEntity<List<CategoryResponseDto>> getAllCategories() {
        List<Category> categories = categoryRepo.findAll();
        List<CategoryResponseDto> data = categories.stream()
                .map(c -> CategoryResponseDto.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .type(c.getType())
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(data);
    }
}