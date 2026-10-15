package vn.edu.huit.finance.core.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.huit.finance.core.Entity.SavingGoal;
import java.util.List;

public interface SavingGoalRepository extends JpaRepository<SavingGoal, Long> {
    List<SavingGoal> findByUserIdOrderByCreatedAtDesc(Long userId);
}