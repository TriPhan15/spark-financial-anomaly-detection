# 08. Kiểm thử và vận hành

## 1. Ba lớp kiểm thử

| Lớp | File | Chạy bằng | Cần gì đang chạy |
|---|---|---|---|
| Kiểm thử đầu cuối API (20 check) | `scripts/e2e_check.py` | `python -X utf8 scripts/e2e_check.py --base-url http://127.0.0.1:8001` | `fraud-api`, file `creditcard.csv` |
| Case Flutter theo mức (44 case) | `mobile-app/test/bigdata_cases/` | `bash test/bigdata_cases/run_in_order.sh` (từ `mobile-app/`) | `fraud-api` |
| Pytest hợp đồng Model A (3 test) | `test_main.py` | `pytest test_main.py` | Không, chạy với `FRAUD_LOAD_MODEL=false` |

`e2e_check.py` chỉ dùng thư viện chuẩn của Python, không cần cài gì. Thêm `-X utf8` trên Windows để in được tiếng Việt.

## 2. `e2e_check.py`: 20 check

Kết quả chạy ngày 15/10/2026 trên mô hình `behavior-gbt-2026-10-07`: **20/20 PASS**.

| # | Check | Kết quả đo được |
|---|---|---|
| 1 | `/health`: cả hai mô hình đã nạp | PASS |
| 2 | Model A gắn cờ ít nhất 15 trong 20 dòng gian lận thật đầu tiên của Kaggle | 20/20 |
| 3 | 422 khi có trường lạ | PASS |
| 4 | 422 khi số tiền âm | PASS |
| 5 | 422 khi lịch sử quá 200 phần tử | PASS |
| 6 | Cold start (4 giao dịch trước) không được chấm | `scored: false`, `insufficient_history` |
| 7 | Lịch sử toàn sau ngày hóa đơn bị bỏ qua | `scored: false` |
| 8 | Bữa trưa thường lệ (52.000) không bất thường | risk 0,019442, ngưỡng 0,961 |
| 9 | Bữa trưa khai 5.000.000 bị luật gắn cờ, và báo đúng là luật | risk 0,019388, `flaggedBy: rule` |
| 10 | Bữa trưa thường lệ cũng không bị luật gắn cờ | PASS |
| 11 | Độ trễ dưới timeout 3 giây của app | 505 ms |
| 12 | Danh mục tiếng Việt được ánh xạ (Ăn uống thành `food_dining` = 1) | PASS |
| 13 | Mua online 1.200.000 lúc 23:40, khoảng 11 lần mức chi trung bình, bất thường và có câu giải thích | risk 0,978872, "Số tiền gấp 11,1 lần mức chi trung bình của bạn." |
| 14 | `scan-history`: quét tất cả, chấm tất cả trừ 5 giao dịch đầu | 40 quét, 35 chấm |
| 15 | `scan-history` gắn cờ giao dịch bất thường được chèn vào | PASS |
| 16 | Điểm của `scan-history` bằng điểm của `predict-anomaly` cho cùng giao dịch | 0,978872 cả hai |
| 17 | 422 khi `scan-history` quá 1.000 giao dịch | PASS |
| 18 | Quét 1.000 giao dịch dưới 10 giây | 1,2 giây, 995 được chấm |
| 19 | Parity: feature của API khớp Spark trên 30 giao dịch test | PASS |
| 20 | Parity: xác suất của API khớp Spark trên 30 giao dịch test | PASS |

Trên 30 giao dịch replay (15 gian lận, 15 bình thường), API cho TP 11, FP 0, FN 4, TN 15. Đây chỉ là mẫu nhỏ để kiểm tra khớp số, không phải số liệu đánh giá mô hình.

Lịch sử dùng cho các check 6 đến 18 là một người dùng giả lập: ăn trưa 45 đến 57 nghìn mỗi ngày lúc 12:10 và đi chợ 280 đến 320 nghìn chiều cuối tuần, trong 30 ngày trước 06/10/2026 12:15. Cùng dữ liệu đó được xuất thành các file trong `docs/demo/`.

## 3. Case Flutter theo mức

`run_in_order.sh` chạy từ mức thấp lên và dừng ở mức đầu tiên bị lỗi, vì một mức chỉ có nghĩa khi các mức dưới nó đã xanh. Mức L0 gọi `/health` và chạy chính `e2e_check.py`.

| Mức | Ý nghĩa | Số case | Mô hình |
|---|---|---|---|
| L0 | API lên, đủ mô hình, `e2e_check.py` đạt | 20 check | Thật |
| L1 | Sự cố: không cấu hình, chế độ demo, API sập, treo, trả 500, trả body lạ, backend lỗi, chưa có lịch sử, chỉ 4 giao dịch | 9 | API thật ở L1-08 và L1-09, các case còn lại giả lập sự cố hoặc không gọi tới API |
| L2 | Hợp đồng dữ liệu: payload hợp lệ, cắt lịch sử còn 100, bỏ khoản thu, lịch sử sau ngày hóa đơn, thứ tự không ảnh hưởng, bỏ số tiền 0, danh mục lạ, kết quả lặp lại được, cắt 1.000 cho màn hình nghi vấn | 9 | Thật |
| L3 | Phán đoán của mô hình: bữa trưa thường, mua online đêm khuya, khai khống 5 triệu, mọi số từ 10 lần trở lên đều bị gắn cờ, sửa giao dịch cũ, độ trễ, màn hình nghi vấn | 8 | Thật |
| L4 | Màn hình khi bị gắn cờ: nhập tay (L4-01 đến 05), sửa (L4-06 đến 09), quét hóa đơn với bút chì (L4-10 đến 14) | 14 | Giả lập đáp án |
| L5 | Luồng bình thường không hiện cảnh báo, và câu riêng cho cờ do luật | 4 | Giả lập đáp án |

L4 và L5 giả lập câu trả lời của mô hình để dựng đúng hộp thoại cần kiểm tra. Phán đoán thật của mô hình được kiểm ở L3. Khi bảo vệ cần nói rõ điểm này.

Một vài case đáng chú ý:

- L1-04: API nhận kết nối nhưng không trả lời. App bỏ cuộc đúng ở mốc 3 giây và vẫn lưu.
- L3-04: không có số tiền vô lý nào lọt. Mọi số từ 10 lần mức trung bình trở lên đều bị gắn cờ.
- L4-01, L4-06, L4-11: chọn Hủy thì không có request lưu hay cập nhật nào được gửi đi.
- L4-12: chọn Vẫn lưu sau khi sửa bằng bút chì thì core-backend nhận đúng số đã khai, dữ liệu OCR vẫn được giữ.
- L4-14: hộp thoại bút chì từ chối 0 và chữ.

## 4. Kịch bản kiểm thử thủ công trên điện thoại

Xếp từ tình huống xấu nhất đến tốt nhất. "Rất lớn" nghĩa là từ khoảng 10 lần mức chi trung bình của tài khoản trở lên.

| # | Thao tác | Mong đợi |
|---|---|---|
| 1 | Tắt `fraud-api`, lưu một khoản chi rất lớn ở cả ba đường | Không cảnh báo, vẫn lưu |
| 2 | Tài khoản mới (dưới 5 giao dịch), lưu khoản chi rất lớn | Không cảnh báo, vẫn lưu |
| 3 | Quét hóa đơn mà OCR không đọc được tổng, dùng bút chì nhập | Dùng số đã nhập, có nhãn "(đã sửa)" |
| 4 | Quét hóa đơn, bút chì, khai số rất lớn, Lưu | Hộp thoại. Hủy thì không lưu. Vẫn lưu thì lưu đúng số đã khai |
| 5 | Thêm giao dịch, Tiền chi, số rất lớn | Cùng hộp thoại |
| 6 | Sửa một khoản chi, tăng số tiền. Lần sau chỉ đổi ghi chú | Lần đầu có hộp thoại, lần sau không |
| 7 | Thêm giao dịch, Tiền thu, số lớn | Không cảnh báo |
| 8 | Sau bước 4 (Vẫn lưu), mở banner Trang chủ hoặc thẻ tab AI | Giao dịch vừa lưu có trong danh sách nghi vấn |
| 9 | Quét hóa đơn bình thường, giữ nguyên số tiền | Không hộp thoại, giao dịch được lưu |

## 5. Dựng môi trường demo cục bộ

Thứ tự đã dùng được khi server deploy không truy cập được.

### 5.1 Core-backend

1. Tạo file `.env` ở gốc repo (bị gitignore). Core-backend cần ít nhất các biến `SPRING_DATASOURCE_*`, `SPRING_JPA_HIBERNATE_DDL_AUTO=update`, `DB_PASSWORD`, `REDIS_PASSWORD`, `CLOUDINARY_*` (giá trị giả được nếu không upload ảnh).
2. `docker compose up -d db redis`, rồi `docker compose up -d --no-deps core-api`. Không bật `data-mining-api` nếu máy thiếu trọng số DONUT, nó sẽ crash.
3. Nếu đăng ký tài khoản báo trùng khóa chính (`users_pkey`), các sequence PostgreSQL đang đứng sau id của dữ liệu seed. Đặt lại sequence bằng `setval`.
4. Đăng ký tài khoản demo, nạp số dư ví đủ lớn để thanh toán (checkout trừ ví).
5. `python BigData/scripts/seed_demo_history.py --base-url http://localhost:8080 --email <email> --password <mật khẩu>`.

### 5.2 API Big Data

```bash
cd BigData
docker compose -f docker-compose.bigdata.yml --profile api up -d --build fraud-api
curl http://127.0.0.1:8001/health
```

Chỉ bật `fraud-api`, không bật cụm Spark, vì Spark master chiếm cổng 8080 của core-backend.

### 5.3 App trên điện thoại Android

```bash
adb reverse tcp:8080 tcp:8080
adb reverse tcp:8001 tcp:8001
cd mobile-app
flutter run --dart-define=API_BASE=http://localhost:8080 --dart-define=FRAUD_API_URL=http://localhost:8001
```

Bản debug cần cho phép HTTP không mã hóa (`usesCleartextTraffic` trong manifest debug) và một file `android/app/src/debug/google-services.json` (bị gitignore). Với file giả, đăng nhập Google và Firebase không chạy, dùng đăng nhập bằng email.

## 6. Xử lý sự cố

| Triệu chứng | Kiểm tra | Cách xử lý |
|---|---|---|
| App lưu mà không bao giờ cảnh báo | App có được build với `FRAUD_API_URL` không? Tài khoản có từ 5 khoản chi trước thời điểm đó không? Số tiền có đủ lớn không? | Build lại với `--dart-define`, chạy `seed_demo_history.py`, tăng số tiền |
| `/health` có `behaviorModelLoaded: false` | `docker compose -f docker-compose.bigdata.yml logs fraud-api` | Thường do file mô hình hỏng hoặc metadata lệch code. Checkout lại `behavior_model/`, build lại image |
| Log có `ChecksumException` | File mô hình bị đổi kết thúc dòng | `.gitattributes` ở gốc repo đặt `-text` cho hai thư mục mô hình. Checkout lại chúng |
| API trả 503 | Mô hình chưa nạp | Như trên |
| Màn hình nghi vấn báo "Chưa kiểm tra được" | API tắt, sai địa chỉ, quá 10 giây | Kiểm tra `adb reverse tcp:8001` và `FRAUD_API_URL` |
| Màn hình nghi vấn báo "Chưa đủ dữ liệu" | Tài khoản có từ 5 khoản chi trở xuống, nên không khoản nào có đủ 5 khoản trước nó | Chạy `seed_demo_history.py` |
| `e2e_check.py` báo lỗi mã hóa khi in | Console Windows dùng cp1252 | Chạy với `python -X utf8` |
| `e2e_check.py` FAIL ở check Model A | Thiếu `creditcard.csv` trong `BigData/data/` (hoặc `BigData/`) | Tải từ Kaggle (`mlg-ulb/creditcardfraud`) |
| Spark master không lên | Cổng 8080 đang bị core-backend dùng | Tắt một trong hai |

## 7. Danh sách kiểm tra trước buổi bảo vệ

- [ ] `curl http://127.0.0.1:8001/health` trả cả hai mô hình đã nạp.
- [ ] `python -X utf8 scripts/e2e_check.py` ra 20/20.
- [ ] `bash test/bigdata_cases/run_in_order.sh` ra "All levels passed."
- [ ] Tài khoản demo có lịch sử (banner Trang chủ hiện số giao dịch bất thường).
- [ ] Đã thử một lần hộp thoại ở cả ba đường nhập trên điện thoại.
- [ ] Nếu demo phần cụm: `bash scripts/preflight.sh` báo `preflight ok`, UI 9870 thấy 2 datanode đang sống.
- [ ] Mở sẵn notebook đã chạy, UI 9870 và 8080 (nếu demo phần cụm), Swagger `/docs`.
