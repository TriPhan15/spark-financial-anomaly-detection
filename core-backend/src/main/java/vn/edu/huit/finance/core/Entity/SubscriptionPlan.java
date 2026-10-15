package vn.edu.huit.finance.core.Entity;
import org.hibernate.envers.Audited;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
@Audited
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String planName;
    private Double price;
    private Integer durationDays;
    private Integer maxAiScans;
    private boolean isWcmSpadeEnabled;

    private boolean active = true;
}