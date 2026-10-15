# 09. Kịch bản quay video demo

Video đi qua toàn bộ phần Big Data theo đúng luồng dữ liệu: HDFS, Spark, mô hình, API, rồi ứng dụng. Mỗi cảnh có: mục tiêu, thao tác, thứ phải thấy trên màn hình, và lời đọc. Thời lượng dự kiến khoảng 15 phút sau khi cắt.

Các kết quả ghi trong tài liệu này đã được chạy thử trên API thật ngày 15/10/2026 (mô hình `behavior-gbt-2026-10-07`). Phần app được tính trước bằng cách gửi đúng dữ liệu mà `seed_demo_history.py` tạo ra.

## 0. Chuẩn bị

### 0.1 Công cụ quay

- Quay màn hình máy tính: OBS Studio, hoặc Xbox Game Bar (Win + Alt + R).
- Chiếu màn hình điện thoại lên máy tính: `scrcpy` (cắm cáp, bật USB debugging), chạy `scrcpy --max-size 1024 --stay-awake`. Quay cửa sổ scrcpy cạnh terminal.
- Terminal: cỡ chữ 16 đến 18, nền tối, xóa lịch sử lệnh cũ (`clear`).
- Trình duyệt: mở sẵn các tab, phóng to 125%.
- Tắt thông báo Windows và điện thoại (chế độ Không làm phiền).

### 0.2 Thứ tự quay

Quay thành hai phần vì Spark master và core-backend cùng dùng cổng 8080:

- Phần A (cảnh 1 đến 5): cụm HDFS và Spark. Core-backend phải tắt.
- Phần B (cảnh 6 đến 11): API và ứng dụng. Cụm Spark tắt, core-backend và `fraud-api` bật.

### 0.3 Việc làm trước khi bấm quay

Phần A:

```bash
cd BigData/BigData
ls data/fraudTrain.csv data/fraudTest.csv creditcard.csv      # phải có đủ 3 file
docker compose -f docker-compose.bigdata.yml up -d --build    # chạy trước một lần cho image sẵn
```

Mở sẵn trong trình duyệt: http://localhost:9870, http://localhost:8080, file `docs/architecture/bigdata-dataflow.html` (từ gốc repo). Mở sẵn notebook `behavior_anomaly_pipeline.ipynb` trong VS Code (đã có output, không cần chạy lại).

Phần B (làm sau khi quay xong phần A):

```bash
docker compose -f docker-compose.bigdata.yml stop spark-master spark-worker namenode datanode
docker compose -f docker-compose.bigdata.yml --profile api up -d fraud-api
# core-backend (từ gốc repo, cần file .env, xem 08_KIEM_THU_VAN_HANH.md mục 5)
docker compose up -d db redis && docker compose up -d --no-deps core-api
```

- Tạo một tài khoản demo mới, nạp số dư ví đủ lớn (khoảng 50 triệu; trên môi trường cục bộ có thể đặt thẳng cột `wallet_balance` trong PostgreSQL), vì mỗi khoản chi được lưu qua checkout sẽ trừ ví. Rồi chạy đúng một lần:
  `python BigData/BigData/scripts/seed_demo_history.py --base-url http://localhost:8080 --email <email> --password <mật khẩu>`.
  Script phải in `Created 41/41 transactions`. Không chạy hai lần, vì lịch sử sẽ khác với dự tính trong kịch bản.
- Điện thoại: `adb reverse tcp:8080 tcp:8080`, `adb reverse tcp:8001 tcp:8001`, rồi `flutter run --dart-define=API_BASE=http://localhost:8080 --dart-define=FRAUD_API_URL=http://localhost:8001`. Đăng nhập tài khoản demo.
- Trong máy điện thoại có sẵn một ảnh hóa đơn bất kỳ trong thư viện ảnh.
- Mở sẵn http://localhost:8001/docs và thư mục `BigData/BigData/docs/demo/` trong VS Code.

Kiểm tra nhanh trước khi quay phần B:

```bash
curl http://127.0.0.1:8001/health      # behaviorModelLoaded: true
```

## Phần A: lưu trữ và xử lý

### Cảnh 1. Mở đầu (khoảng 45 giây)

Màn hình: sơ đồ `docs/architecture/bigdata-dataflow.html`.

Thao tác: rê chuột theo luồng từ trái sang phải: CSV, HDFS, Spark, mô hình, API, app.

> Xin chào thầy cô và các bạn. Nhóm 7 xin trình bày phần Big Data của đề tài "Phát hiện giao dịch tài chính bất thường, tích hợp cảnh báo rủi ro cho hệ thống quản lý tài chính cá nhân DonutPay".
> Bài toán là: khi người dùng ghi một khoản chi lệch xa thói quen của chính họ, ứng dụng hỏi lại trước khi lưu.
> Luồng dữ liệu đi như trên sơ đồ. Dữ liệu thô gần 1,9 triệu giao dịch được đưa lên HDFS. Spark đọc từ HDFS, tính đặc trưng hành vi cho từng người và huấn luyện mô hình. Mô hình được một API nạp vào, và ứng dụng gọi API này mỗi khi người dùng lưu một khoản chi.

### Cảnh 2. Khởi động cụm (khoảng 45 giây)

Thao tác:

```bash
cd BigData/BigData
docker compose -f docker-compose.bigdata.yml up -d
docker compose -f docker-compose.bigdata.yml ps
```

Phải thấy: 4 container `namenode`, `datanode`, `spark-master`, `spark-worker` đang chạy.

> Toàn bộ hạ tầng chạy bằng Docker Compose, tách riêng khỏi hệ thống chính. Có bốn container: namenode và datanode của HDFS dùng Hadoop 3.4.1, và một Spark master cùng một worker 4 nhân, 3 GB dùng Spark 4.0.1.

### Cảnh 3. Nạp dữ liệu lên HDFS (khoảng 1 phút 30 giây)

Thao tác:

```bash
MSYS_NO_PATHCONV=1 bash scripts/hdfs_load.sh
```

Khi chạy xong, cuộn lên chỉ vào ba chỗ: danh sách file, dòng `622.2 M`, dòng `The filesystem under path '/data' is HEALTHY`.

Chuyển sang http://localhost:9870, vào Utilities > Browse the file system > `data` > `sparkov`. Chỉ vào cột Block Size (128 MB) và Replication (1). Bấm vào `fraudTrain.csv` để hiện danh sách block.

> Script nạp ba file CSV lên HDFS. Hai file Sparkov gồm 1,85 triệu giao dịch thẻ có nhãn gian lận, dùng để huấn luyện mô hình chính. File thứ ba là bộ Credit Card của Kaggle, dùng làm mô hình đối chứng.
> Tổng dung lượng trên HDFS là 622 MB. Lệnh fsck báo hệ thống tệp khỏe mạnh.
> Trên giao diện namenode, file được chia thành các block 128 MB. Vì chạy trên một máy nên chỉ có một datanode và hệ số nhân bản là 1. Kiến trúc giữ nguyên khi thêm datanode.

### Cảnh 4. Spark huấn luyện Model B (khoảng 2 phút sau khi cắt)

Thao tác: mở http://localhost:8080, chỉ vào worker 4 core. Rồi chạy:

```bash
MSYS_NO_PATHCONV=1 bash scripts/train_on_cluster.sh behavior
```

Trong lúc job chạy (khoảng 6 phút), chuyển sang http://localhost:4040: tab Jobs, tab Stages, tab Storage. Khi dựng video, tua nhanh hoặc cắt đoạn chờ. Kết thúc ở output JSON cuối của job: `"chosen": "gbt"`, `"threshold": 0.961`, các chỉ số test.

> Job huấn luyện chạy trên cụm Spark, đọc thẳng từ địa chỉ hdfs, không đọc file trên máy.
> Bước nặng nhất là hàm cửa sổ: Spark gom giao dịch của từng chủ thẻ, sắp theo thời gian, và với mỗi giao dịch lấy 100 giao dịch trước đó của chính người đó. Từ đó tính bảy đặc trưng hành vi, ví dụ số tiền gấp bao nhiêu lần mức trung bình của người đó, giờ giao dịch, số giao dịch trong 24 giờ qua.
> Dữ liệu được chia theo thời gian, không xáo trộn: phần đầu để huấn luyện, phần sau để chọn ngưỡng, và file test ở giai đoạn sau nữa để đánh giá. Tỉ lệ gian lận chỉ khoảng nửa phần trăm, nên nhóm đặt trọng số 182 cho lớp gian lận.
> Spark huấn luyện hai mô hình, Random Forest và Gradient Boosted Trees, rồi chọn mô hình theo PR-AUC trên tập validation. GBT thắng, với ngưỡng cảnh báo 0,961.
> Toàn bộ job chạy khoảng 6 phút. Chỉ các số liệu tổng hợp được đưa về driver, không có bước nào gom toàn bộ dữ liệu về Python.

### Cảnh 5. Kết quả và biểu đồ (khoảng 1 phút 45 giây)

Màn hình: notebook `behavior_anomaly_pipeline.ipynb` trong VS Code.

Thao tác, cuộn lần lượt:

1. Mục 2: biểu đồ tỉ lệ gian lận theo giờ và theo danh mục.
2. Mục 3: bảng trung vị đặc trưng của hai nhóm, biểu đồ tỉ lệ gian lận theo mức lệch thói quen.
3. Mục 4: bảng so sánh GBT, Random Forest và luật đơn giản; hình đường PR, ma trận nhầm lẫn, độ quan trọng đặc trưng.
4. Mục 6: thí nghiệm điền 0 của Model A.

> Phân tích khám phá cho thấy gian lận tập trung vào ban đêm và ở một số danh mục mua sắm. Trung vị số tiền của giao dịch gian lận gấp gần 5 lần mức trung bình của chính người đó, còn giao dịch bình thường chỉ khoảng 0,7 lần.
> Trên tập test, GBT đạt PR-AUC 0,654, precision 0,59, recall 0,67. Mô hình chỉ cảnh báo 0,42% giao dịch, và gần 6 trên 10 cảnh báo là gian lận thật. Luật đơn giản "gấp 3 lần trung bình" cảnh báo gần 7% giao dịch nhưng hầu hết là nhầm, F1 thấp hơn hơn 12 lần.
> Đặc trưng quan trọng nhất là tỉ lệ số tiền so với mức trung bình của người dùng, rồi đến danh mục và giờ.
> Thí nghiệm cuối trả lời câu hỏi: sao không dùng mô hình ngân hàng kiểu Kaggle cho app? Mô hình đó cần 28 biến PCA mà app không có. Nếu điền 0 cho các biến đó, recall rơi từ 0,79 về 0, mô hình không bắt được giao dịch gian lận nào. Vì vậy nhóm làm Model B với đặc trưng mà app thật sự có.

Kết thúc phần A: tắt cụm.

```bash
docker compose -f docker-compose.bigdata.yml stop spark-master spark-worker namenode datanode
```

## Phần B: phục vụ và ứng dụng

### Cảnh 6. API (khoảng 30 giây)

Thao tác:

```bash
docker compose -f docker-compose.bigdata.yml --profile api up -d fraud-api
curl http://127.0.0.1:8001/health
```

Phải thấy: `"behaviorModelLoaded": true`, `"behaviorModelVersion": "behavior-gbt-2026-10-07"`.

> Mô hình đã huấn luyện được đóng gói vào một API FastAPI. Khi khởi động, API nạp cả hai mô hình, kiểm tra danh sách đặc trưng trong metadata khớp với code, và chạy thử một lần cho nóng máy.

### Cảnh 7. Thử API trên Swagger (khoảng 2 phút 30 giây)

Màn hình: http://localhost:8001/docs. Với mỗi file, mở trong VS Code, Ctrl+A, Ctrl+C, dán vào ô Request body của Swagger (Try it out), bấm Execute.

| Bước | Endpoint | File trong `docs/demo/` | Phải thấy |
|---|---|---|---|
| 7.1 | `POST /v2/predict-anomaly` | `01_binh_thuong.json` | `isAnomaly: false`, `riskScore: 0.019442` |
| 7.2 | `POST /v2/predict-anomaly` | `02_mua_online_dem_khuya.json` | `isAnomaly: true`, `riskScore: 0.978872`, `flaggedBy: "model"`, "Số tiền gấp 11,1 lần mức chi trung bình của bạn." |
| 7.3 | `POST /v2/predict-anomaly` | `03_khai_khong_bua_trua.json` | `isAnomaly: true`, `riskScore: 0.019388`, `flaggedBy: "rule"`, "Số tiền gấp 98,0 lần ... cho danh mục này." |
| 7.4 | `POST /v2/predict-anomaly` | `04_cold_start.json` | `scored: false`, `reason: "insufficient_history"` |
| 7.5 | `POST /v2/scan-history` | `05_scan_history.json` | `scannedCount: 40`, `scoredCount: 35`, `flaggedCount: 1` |
| 7.6 | `POST /predict-fraud` | `06_model_a_gian_lan_that.json` | `is_fraud: true`, `risk_score: 0.9713` |
| 7.7 | `POST /predict-fraud` | `07_model_a_dien_0.json` | `is_fraud: false`, `risk_score: 0.0538` |

Lời đọc theo từng bước:

> 7.1. Đây là một người dùng có 39 giao dịch trong 30 ngày: ăn trưa khoảng 50 nghìn mỗi ngày, đi chợ khoảng 300 nghìn cuối tuần. App chỉ gửi giao dịch thô, còn API tự tính bảy đặc trưng bằng đúng công thức lúc huấn luyện. Bữa trưa 52 nghìn có điểm rủi ro 0,02, không bất thường.

> 7.2. Cùng người đó, mua sắm online 1,2 triệu lúc 23 giờ 40. Điểm rủi ro 0,979, vượt ngưỡng 0,961. API trả kèm lý do bằng tiếng Việt: số tiền gấp 11 lần mức chi trung bình.

> 7.3. Trường hợp khai khống: bữa trưa khai thành 5 triệu. Bộ Sparkov gần như không có gian lận ở danh mục ăn uống, nên mô hình chỉ cho 0,02. Nhóm thêm một luật chặn thói quen: khoản chi gấp từ 10 lần trở lên so với cả mức trung bình chung và mức trung bình của danh mục thì vẫn bị gắn cờ. Trường flaggedBy ghi rõ là luật, không phải mô hình, để không đánh tráo kết quả.

> 7.4. Người dùng mới chỉ có 4 giao dịch: API không chấm, vì không đủ dữ liệu để biết thói quen. App sẽ lưu bình thường.

> 7.5. Endpoint scan-history chấm cả lịch sử trong một lần gọi, dùng cho màn hình giao dịch nghi vấn. 40 giao dịch, 5 giao dịch đầu chưa đủ lịch sử, 35 được chấm, 1 bị gắn cờ, đúng là giao dịch bất thường đã chèn vào.

> 7.6 và 7.7. Cuối cùng là Model A trên một giao dịch gian lận thật của Kaggle: điểm 0,97, bị gắn cờ. Cùng giao dịch đó, thay 28 biến PCA bằng 0: điểm còn 0,05, không bị gắn cờ. Đây là thí nghiệm điền 0 nhìn thấy trực tiếp.

### Cảnh 8. Kiểm thử đầu cuối (khoảng 45 giây)

Thao tác:

```bash
python -X utf8 scripts/e2e_check.py --base-url http://127.0.0.1:8001
```

Phải thấy: 20 dòng PASS, dòng cuối `20/20 checks passed`. Chỉ vào hai dòng `parity`.

> Script kiểm thử đầu cuối chạy 20 kiểm tra trên API thật: dữ liệu sai định dạng bị từ chối, người dùng mới không bị chấm, độ trễ khoảng nửa giây, quét 1.000 giao dịch trong khoảng 1 giây.
> Hai dòng quan trọng nhất là parity: 30 giao dịch test do Spark xuất ra được gửi lại qua API. Đặc trưng và xác suất API tính ra khớp tuyệt đối với Spark. Nghĩa là mô hình chạy trong app chính là mô hình đã được đánh giá.

### Cảnh 9. Ứng dụng: màn hình giao dịch nghi vấn (khoảng 1 phút)

Màn hình: cửa sổ scrcpy, app đã đăng nhập tài khoản demo vừa seed.

Thao tác:

1. Ở Trang chủ, chỉ vào banner đỏ "Có 2 giao dịch chi tiêu bất thường". Bấm vào.
2. Màn hình "Cảnh báo chi tiêu bất thường": dòng "2/36 giao dịch có dấu hiệu bất thường" (36 hoặc 37 tùy ngày chạy seed), biểu đồ cột (Mua sắm 1, Hóa đơn 1), rồi danh sách:
   - Mua sắm 2.400.000 đ: thanh rủi ro khoảng 97%, "Số tiền gấp 10,7 lần mức chi trung bình của bạn." (10,7 đến 10,8)
   - Hóa đơn 3.000.000 đ: nhãn "Vượt xa mức chi thường ngày", "Số tiền gấp ... lần mức chi trung bình của bạn." (khoảng 26 đến 28)
3. Quay lại, vào tab AI, chỉ vào thẻ "Cảnh báo chi tiêu bất thường" với dòng "2 giao dịch có dấu hiệu bất thường. Nhấn để xem."

Các con số trên đúng khi tài khoản chỉ có 41 giao dịch do script seed tạo. Nếu tài khoản có giao dịch khác, số có thể lệch.

> Đây là ứng dụng DonutPay. Tài khoản demo có khoảng một tháng chi tiêu thói quen và ba khoản lạ.
> Trang chủ báo có 2 giao dịch bất thường. Màn hình chi tiết quét lại toàn bộ lịch sử bằng mô hình mỗi khi mở: khoảng 36 giao dịch đủ dữ liệu, 2 bị gắn cờ, có biểu đồ theo danh mục.
> Khoản mua sắm 2,4 triệu lúc gần nửa đêm do mô hình gắn cờ, rủi ro 97%. Khoản hóa đơn 3 triệu lúc 2 giờ sáng do luật chặn gắn cờ, nên app hiện nhãn "vượt xa mức chi thường ngày" thay vì điểm rủi ro, để người dùng không hiểu nhầm.
> Khoản tiệc nhà hàng 1,5 triệu thì không bị gắn cờ: nó gấp khoảng 7 lần mức chi trung bình chung, chưa tới 10 lần của luật, và mô hình cho điểm thấp vì ăn uống là danh mục ít gian lận trong dữ liệu huấn luyện. Hệ thống không cảnh báo mọi khoản lớn.

### Cảnh 10. Ứng dụng: ba đường nhập khoản chi (khoảng 3 phút 30 giây)

Thứ tự dưới đây đã được tính để các bước Hủy không làm thay đổi lịch sử, và các bước có lưu nằm ở cuối.

**10.1 Khoản chi bình thường.** Bấm thêm giao dịch, "Thêm giao dịch", loại "Tiền chi", số tiền 50.000, danh mục Ăn uống, "Lưu giao dịch". Lưu ngay, không có hộp thoại.

> Trước hết là trường hợp bình thường: một bữa trưa 50 nghìn. Ứng dụng hỏi mô hình, mô hình thấy bình thường, giao dịch được lưu ngay, người dùng không thấy gì khác.

**10.2 Khoản thu lớn.** "Thêm giao dịch", loại "Tiền thu", 10.000.000, "Lưu giao dịch". Lưu ngay, không có hộp thoại.

> Khoản thu không được gửi tới mô hình, vì mô hình chỉ học hành vi chi tiêu.

**10.3 Quét hóa đơn, khai khống bằng nút bút chì, rồi Hủy.**

1. Bấm nút quét, "Quét hóa đơn bằng AI", "Từ thư viện", chọn ảnh hóa đơn.
2. Màn hình "Xác nhận hóa đơn". Nếu dịch vụ OCR không chạy, app báo "Quét hóa đơn thất bại (Kiểm tra AI service). Vui lòng nhập tay."
3. Bấm bút chì cạnh "TỔNG TIỀN:", nhập 5000000, "Áp dụng". Thấy "(đã sửa)" cạnh tổng tiền.
4. Danh mục giữ "Ăn uống". Bấm "Xác nhận & Lưu CSDL".
5. Hộp thoại "Chi tiêu bất thường so với thói quen": 5.000.000 đ, câu "Số tiền gấp ... lần mức chi trung bình của bạn cho danh mục này.", dòng "Khoản này vượt xa mức bạn thường chi nên cần bạn xác nhận."
6. Bấm "Hủy". Quay lại sổ giao dịch, chỉ ra không có khoản 5 triệu nào được lưu.

> Đường thứ nhất là quét hóa đơn. Trong môi trường demo này dịch vụ OCR không bật, nên ứng dụng báo lỗi và cho nhập tay bằng nút bút chì. Nút này cũng dùng khi OCR đọc sai tổng tiền.
> Tôi khai tổng tiền bữa ăn là 5 triệu, gấp hàng chục lần mức thường ngày. Khi bấm lưu, ứng dụng gửi khoản chi kèm lịch sử lên API trước. Kết quả bị gắn cờ, hộp thoại hiện số tiền và lý do. Vì đây là luật chặn gắn cờ, hộp thoại không hiện điểm rủi ro mà nói rõ khoản này vượt xa mức thường chi.
> Chọn Hủy thì không có gì được lưu.

**10.4 Mô hình gắn cờ, có điểm rủi ro.**

1. Lại "Quét hóa đơn bằng AI", "Từ thư viện", chọn ảnh.
2. Bút chì, nhập 5000000, "Áp dụng".
3. Đổi danh mục sang "Mua sắm".
4. Trong ô "Ngày giao dịch", giữ ngày hôm nay, sửa giờ thành 23:30 (ví dụ `16/10/2026 23:30`).
5. "Xác nhận & Lưu CSDL". Hộp thoại hiện câu giải thích và dòng "Điểm rủi ro: 97.x% (ngưỡng cảnh báo 96.1%)".
6. Bấm "Hủy".

Đã tính trước với dữ liệu seed cộng bữa trưa ở bước 10.1: mô hình cho 0,965 đến 0,973 ở cả bảy ngày trong tuần, trên ngưỡng 0,961 nhưng không xa. Nếu hôm quay hộp thoại không hiện, bỏ bước này: cảnh 7.2 và màn hình nghi vấn ở cảnh 9 đã cho thấy mô hình gắn cờ. Nếu admin có danh mục "Mua sắm online" thì dùng danh mục đó với 3.000.000 lúc 23:30, điểm khoảng 0,98, ổn định hơn.

> Lần này là một khoản mua sắm 5 triệu lúc 11 giờ rưỡi đêm. Ở đây chính mô hình gắn cờ, nên hộp thoại hiện điểm rủi ro 97%, so với ngưỡng 96,1% được chọn trên tập validation. Mô hình dựa vào cả số tiền so với thói quen, danh mục và giờ giao dịch.

**10.5 Nhập tay khoản chi lớn.** "Thêm giao dịch", "Tiền chi", 5.000.000, danh mục Ăn uống, "Lưu giao dịch". Cùng hộp thoại. Bấm "Hủy".

> Đường thứ hai là nhập tay. Nếu chỉ kiểm tra lúc quét hóa đơn thì người dùng có thể né bằng cách nhập tay, nên ở đây cũng kiểm tra, cùng một hộp thoại.

**10.6 Sửa khoản chi.** Vào sổ giao dịch, chọn một khoản "Ăn trưa" của hôm qua hoặc hôm kia, "Sửa giao dịch", đổi số tiền thành 5.000.000, "Lưu thay đổi". Hộp thoại hiện. Bấm "Hủy". Rồi sửa lại chính khoản đó, chỉ đổi ghi chú, "Lưu thay đổi": lưu ngay, không hộp thoại.

> Đường thứ ba là sửa giao dịch. Nếu không kiểm tra ở đây, người dùng có thể lưu một số nhỏ rồi sửa thành số lớn. Khi sửa, ứng dụng so với thói quen tại đúng ngày của giao dịch đó, không phải hôm nay. Chỉ đổi ghi chú thì không cần hỏi mô hình.

**10.7 Vẫn lưu.** Làm lại bước 10.3 nhưng bấm "Vẫn lưu". Giao dịch 5.000.000 được lưu đúng số đã khai.

> Cảnh báo chỉ để người dùng cân nhắc. Chọn Vẫn lưu thì giao dịch được lưu đúng số tiền đã khai.

### Cảnh 11. Khi dịch vụ Big Data gặp sự cố (khoảng 1 phút)

Thao tác:

```bash
docker compose -f docker-compose.bigdata.yml stop fraud-api
```

Trên app: "Thêm giao dịch", "Tiền chi", 8.000.000, danh mục Di chuyển, "Lưu giao dịch". Lưu ngay, không hộp thoại.

```bash
docker compose -f docker-compose.bigdata.yml --profile api up -d fraud-api
```

Đợi `curl http://127.0.0.1:8001/health` trả `behaviorModelLoaded: true` (khoảng 20 đến 30 giây). Trên app, mở lại màn hình nghi vấn (kéo xuống để quét lại): từ 2 lên 4 giao dịch bất thường. Hai khoản mới là Ăn uống 5.000.000 (vừa "Vẫn lưu" ở 10.7) và Di chuyển 8.000.000 (vừa lưu lúc dịch vụ tắt), cả hai có nhãn "Vượt xa mức chi thường ngày".

> Nếu dịch vụ Big Data sập, mất mạng, hay trả lời chậm hơn 3 giây, ứng dụng vẫn lưu bình thường. Cảnh báo là tính năng phụ, không được phép chặn người dùng ghi chi tiêu.
> Khi dịch vụ chạy lại, màn hình nghi vấn quét lại toàn bộ lịch sử, nên khoản 8 triệu lọt qua lúc dịch vụ tắt vẫn được phát hiện, cùng với khoản 5 triệu mà người dùng đã chọn vẫn lưu.

### Cảnh 12. Kiểm thử phía ứng dụng (tùy chọn, khoảng 45 giây)

Thao tác (từ `mobile-app/`):

```bash
bash test/bigdata_cases/run_in_order.sh
```

Tua nhanh, dừng ở dòng cuối "All levels passed."

> Ngoài kiểm thử API, nhóm có 44 case kiểm thử phía ứng dụng, xếp từ tình huống xấu nhất đến tốt nhất: dịch vụ sập, hợp đồng dữ liệu, phán đoán của mô hình thật, rồi các màn hình. Script dừng ngay ở mức đầu tiên bị lỗi. Tất cả các mức đều đạt.

### Cảnh 13. Kết (khoảng 45 giây)

Màn hình: quay lại sơ đồ kiến trúc.

> Tóm lại, nhóm đã dựng HDFS và Spark thật, xử lý 1,85 triệu giao dịch bằng hàm cửa sổ phân tán, huấn luyện và chọn mô hình trên tập validation, rồi đưa mô hình vào ứng dụng qua một API có kiểm chứng khớp số với Spark.
> Giới hạn nhóm nêu rõ: dữ liệu huấn luyện là dữ liệu mô phỏng; PR-AUC 0,654 nghĩa là vẫn có báo nhầm và bỏ sót; hạ tầng chạy trên một máy; và đây là suy luận trực tuyến khi lưu giao dịch, không phải xử lý luồng.
> Cảm ơn thầy cô và các bạn đã theo dõi.

## Phụ lục: bảng thời lượng

| Cảnh | Nội dung | Thời lượng |
|---|---|---|
| 1 | Mở đầu, sơ đồ | 0:45 |
| 2 | Khởi động cụm | 0:45 |
| 3 | Nạp HDFS | 1:30 |
| 4 | Spark huấn luyện | 2:00 |
| 5 | Notebook, kết quả | 1:45 |
| 6 | API | 0:30 |
| 7 | Swagger | 2:30 |
| 8 | e2e_check | 0:45 |
| 9 | Màn hình nghi vấn | 1:00 |
| 10 | Ba đường nhập | 3:30 |
| 11 | Sự cố | 1:00 |
| 12 | Kiểm thử app (tùy chọn) | 0:45 |
| 13 | Kết | 0:45 |
| | Tổng | khoảng 17 phút, khoảng 16 phút nếu bỏ cảnh 12 |

## Phụ lục: nếu kết quả trên app khác kịch bản

| Hiện tượng | Lý do thường gặp | Xử lý |
|---|---|---|
| Không có banner ở Trang chủ | Chưa seed, hoặc `FRAUD_API_URL` chưa truyền khi build | Kiểm tra lại mục 0.3 |
| Hộp thoại không hiện khi khai 5 triệu | Tài khoản có lịch sử chi lớn hơn dự tính | Tăng số tiền lên 20 hoặc 50 triệu |
| Hộp thoại hiện ở bước 10.1 | Đồng hồ điện thoại ở giờ rất khuya, hoặc lịch sử khác dự tính | Dùng số nhỏ hơn hoặc quay ban ngày |
| Màn hình nghi vấn báo "Chưa kiểm tra được" | API tắt hoặc thiếu `adb reverse tcp:8001` | Chạy lại `adb reverse`, kiểm tra `/health` |
