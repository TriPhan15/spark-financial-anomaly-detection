package vn.edu.huit.finance.core;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;
@EnableScheduling
@EnableJpaAuditing
@SpringBootApplication
public class CoreBackendApplication {
	@PostConstruct
	public void init() {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		System.out.println(" Đã cấu hình múi giờ hệ thống: " + TimeZone.getDefault().getID());
	}
	public static void main(String[] args) {
		SpringApplication.run(CoreBackendApplication.class, args);
	}

}
