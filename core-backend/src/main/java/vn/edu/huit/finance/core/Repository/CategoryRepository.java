package vn.edu.huit.finance.core.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.huit.finance.core.Entity.Category;

import java.util.Optional;

public interface CategoryRepository  extends JpaRepository<Category, Long> {
    Optional<Category> findByName(String name);
}
