package vn.edu.huit.finance.core.Repository;

import vn.edu.huit.finance.core.Entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CampaignRepository extends JpaRepository<SubscriptionPlan, Long>{
}
