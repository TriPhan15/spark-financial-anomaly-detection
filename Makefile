.PHONY: check dev-infra clean

# Test all services
check:
	@echo "BẮT ĐẦU KIỂM THỬ TOÀN HỆ THỐNG..."
	@echo "-----------------------------------"
	@echo "1/3: Chạy test AI Service (Python)..."
	cd ai-service && venv/bin/pytest test_main.py -v
	@echo "-----------------------------------"
	@echo "2/3: Chạy test Core Backend (Java)..."
	cd core-backend && mvn -B clean test -Dtest=!CoreBackendApplicationTests
	@echo "-----------------------------------"
	@echo "3/3: Chạy test Mobile App (Flutter)..."
	cd mobile-app && flutter test
	@echo "TOÀN BỘ HỆ THỐNG ĐÃ PASS TEST!"

# build and run all services
dev-infra:
	@echo "Khởi động Backend, AI và Database..."
	docker compose up -d --build --wait

# clean 
clean:
	cd core-backend && mvn clean
	cd mobile-app && flutter clean