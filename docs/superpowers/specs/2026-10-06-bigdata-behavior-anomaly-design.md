# Thiết kế: Cảnh báo chi tiêu bất thường dựa trên hành vi (Model B) cho đồ án Big Data

- Ngày: 2026-10-06
- Phạm vi: `BigData/BigData/` (Spark + FastAPI), `mobile-app/lib/screens/receipt_scanner/review_form_page.dart`, tài liệu kiến trúc (archify)
- Trạng thái: đã triển khai (2026-10-07); bổ sung mục 16 ngày 2026-10-08

## 1. Bối cảnh và vấn đề

Đề tài đã đăng ký: "Phát hiện giao dịch tài chính bất thường (tích hợp tính năng cảnh báo rủi ro cho hệ thống quản lý tài chính cá nhân)". Form đăng ký cho phép "dữ liệu Kaggle kết hợp cấu trúc dữ liệu mô phỏng" và "Isolation Forest hoặc Random Forest".

Hiện trạng:

- Model hiện có (gọi là **Model A**) là Spark `RandomForestClassificationModel` train trên Kaggle `creditcard.csv` (284.807 dòng), cần đúng 30 feature `Time, V1..V28, Amount`. Kết quả trên tập test random 20%: Precision 0,348, Recall 0,796, F1 0,484, PR-AUC 0,707.
- App chỉ có dữ liệu hóa đơn (DONUT): số tiền, ngày giờ, tên cửa hàng, danh mục. Không có `V1..V28`, `Time` của Kaggle cũng không cùng nghĩa với giờ trên hóa đơn.
- Code Flutter hiện tại fail-safe đúng (không zero-pad), nên trên app thật tính năng cảnh báo **không bao giờ kích hoạt**. Tiêu chí "Trực quan hóa/Xây dựng ứng dụng" (2,5 điểm) đang hổng.
- Zero-padding `V1..V28` bị loại: giá trị 0 nằm trong vùng giao dịch bình thường, nên model gần như luôn trả "an toàn". Đó là dự đoán ngoài miền dữ liệu, không phải imputation.

## 2. Mục tiêu và tiêu chí thành công

Mục tiêu: cảnh báo trên app là **kết quả suy luận thật** từ một model train bằng Spark trên dữ liệu lớn, dùng đúng các feature app thu được; đồng thời đáp ứng 85–100% từng tiêu chí rubric.

Tiêu chí thành công:

1. Dữ liệu train nằm trên HDFS thật (Docker), Spark đọc qua `hdfs://`.
2. Feature hành vi được tính bằng Spark Window functions trên ~1,85 triệu dòng.
3. Model B có metric báo cáo trên tập test tách theo thời gian: Precision, Recall, F1, PR-AUC, ROC-AUC, confusion matrix, feature importance; có so sánh ít nhất 2 thuật toán.
4. Feature tính lúc serve (Python) khớp feature tính lúc train (Spark) với sai số ≤ 1e-9 trên fixture chung.
5. App gọi API v2 với dữ liệu thật của user, hiện dialog cảnh báo kèm giải thích khi model kết luận bất thường; mọi lỗi đều fail-open (vẫn lưu bình thường).
6. Có thí nghiệm số liệu chứng minh Model A không dùng được cho hóa đơn (zero hóa `V1..V28` trên các dòng fraud của tập test và đo Recall).
7. Có sơ đồ kiến trúc toàn monorepo (archify) làm tài liệu tra cứu.
8. App có giao diện trực quan hóa các giao dịch nghi vấn (form đăng ký cam kết mục này): danh sách giao dịch bị gắn cờ kèm điểm rủi ro và lý do, biểu đồ theo danh mục, lối vào từ tab AI và banner ở Trang chủ (mục 16).

Ngoài phạm vi: sửa core-backend (Spring Boot), xác thực (auth) cho BigData API, triển khai BigData lên VPS production, retrain online.

## 3. Kiến trúc tổng thể: hai model

```
[HDFS Docker: namenode + datanode]
   /data/creditcard/creditcard.csv            -> Model A (benchmark học thuật)
   /data/sparkov/fraudTrain.csv, fraudTest.csv -> Model B (triển khai trong app)
        | spark-submit trong cùng Docker network, đọc hdfs://namenode:8020/...
        v
[Spark training]
   train_pipeline.py   (A, giữ nguyên, bổ sung thí nghiệm zero-hóa V1..V28)
   train_behavior.py   (B, mới: Window functions -> feature hành vi -> RF/GBT)
   -> random_forest_model/ (A), behavior_model/ + behavior_metadata.json + metrics_behavior.json (B)
        v
[BigData FastAPI :8001]
   POST /predict-fraud       (A, giữ nguyên contract 30 feature, chỉ dùng demo benchmark)
   POST /v2/predict-anomaly  (B, mới)
   behavior_features.py: cùng công thức với Spark, có parity test
        v
[Flutter review_form_page.dart]
   lấy lịch sử qua RemoteApiHelper.getTransactions() (core-backend, 100 giao dịch gần nhất)
   -> AnomalyCheckService -> /v2/predict-anomaly -> dialog cảnh báo
   timeout 3 giây, fail-open
```

Model A phục vụ phần đánh giá kỹ thuật xử lý mất cân bằng trên benchmark chuẩn. Model B dùng đúng các feature app thu được, nên cảnh báo trên app là suy luận thật. Tên tính năng trên app: "Cảnh báo chi tiêu bất thường so với thói quen", khớp tên đề tài ("chi tiêu vượt ngưỡng bất thường").

## 4. Dữ liệu

Dataset Model B: Kaggle `kartik2112/fraud-detection` (Sparkov, giấy phép CC0), đã tải về `BigData/BigData/data/` (không commit, `*.csv` đã bị gitignore).

| File | Số dòng | Fraud | Tỉ lệ | Số user (`cc_num`) |
|---|---|---|---|---|
| fraudTrain.csv | 1.296.675 | 7.506 | 0,58% | 983 |
| fraudTest.csv | 555.719 | 2.145 | 0,39% | 924 |

14 category: `entertainment, food_dining, gas_transport, grocery_net, grocery_pos, health_fitness, home, kids_pets, misc_net, misc_pos, personal_care, shopping_net, shopping_pos, travel`.

Cột chỉ dùng: `trans_date_trans_time, cc_num, category, amt, unix_time, is_fraud`. Bỏ toàn bộ cột app không bao giờ có (tọa độ, dân số thành phố, nghề nghiệp, ngày sinh, tên, địa chỉ, merchant).

Giới hạn phải nói rõ trong báo cáo: Sparkov là dữ liệu mô phỏng. Dữ liệu chi tiêu cá nhân có nhãn không được công khai, nên dùng dữ liệu mô phỏng là lựa chọn có lý do. Nhãn "fraud" trong Sparkov được diễn giải trên app là "lệch khỏi thói quen chi tiêu của chủ tài khoản".

## 5. Định nghĩa feature (nguồn sự thật duy nhất)

Ký hiệu: với giao dịch hiện tại `x` của user `u` tại thời điểm `t`, `H` là tối đa **100** giao dịch gần nhất của `u` có thời điểm **nhỏ hơn hẳn** `t`. Giao dịch trùng thời điểm với `t` không thuộc `H` ở cả hai phía. Con số 100 khớp với `getTransactions()` của app (`limit=100`). Nếu Spark tính trên toàn bộ lịch sử thì feature lúc train và lúc serve sẽ lệch.

Thời điểm: dùng ngày giờ địa phương không timezone (`trans_date_trans_time` của Sparkov, ngày giờ trên hóa đơn của app), quy ra giây theo epoch coi như UTC ở cả hai phía (Spark đặt `spark.sql.session.timeZone=UTC`). Không dùng cột `unix_time` của Sparkov vì nó lệch so với `trans_date_trans_time`.

| # | Feature | Công thức | Ghi chú |
|---|---|---|---|
| 1 | `hour` | giờ (0–23) của `t`, giờ địa phương | |
| 2 | `day_of_week` | 0 = Thứ Hai … 6 = Chủ Nhật | Spark: `(dayofweek(t) + 5) % 7` |
| 3 | `category_idx` | index của category Sparkov theo bảng cố định 14 giá trị, sắp xếp alphabet | Không dùng `StringIndexer` theo tần suất, để index ổn định giữa train và serve |
| 4 | `amt_ratio_user` | `amount / mean(amount của H)` | |
| 5 | `amt_ratio_user_cat` | `amount / mean(amount của H cùng category)` | Nếu `H` không có giao dịch cùng category thì bằng `amt_ratio_user` |
| 6 | `txn_count_24h` | số giao dịch trong `H` có thời điểm thuộc `[t − 86400 giây, t)` | Spark: đếm trong khung 100 dòng trước, lọc theo thời gian |
| 7 | `secs_since_prev` | `t − thời điểm giao dịch gần nhất trong H` (giây) | |

Không dùng số tiền tuyệt đối, vì tỉ lệ không phụ thuộc đơn vị tiền tệ (USD trong Sparkov, VND trong app).

**Cold start:** nếu `|H| < 5` thì giao dịch không được chấm điểm. Spark loại các dòng này khỏi cả train lẫn đánh giá; API trả `scored: false, reason: "insufficient_history"`. Metric báo cáo vì vậy phản ánh đúng các giao dịch app thực sự chấm.

**Ánh xạ category app sang Sparkov:** danh mục trong app do admin định nghĩa (động), nên dùng bảng keyword (không phân biệt hoa thường, bỏ dấu tiếng Việt) trong `behavior_features.py`. Ví dụ: "ăn", "uống", "cà phê", "nhà hàng" → `food_dining`; "xăng", "xe", "di chuyển", "grab" → `gas_transport`; "siêu thị", "chợ", "tạp hóa" → `grocery_pos`; "mua sắm", "quần áo" → `shopping_pos`; "giải trí", "phim" → `entertainment`; "sức khỏe", "thuốc", "gym" → `health_fitness`; "nhà", "điện", "nước", "internet" → `home`; "du lịch", "khách sạn", "vé máy bay" → `travel`; "làm đẹp", "cắt tóc" → `personal_care`; "con", "thú cưng" → `kids_pets`; còn lại → `misc_pos`. Ánh xạ nằm ở server để Flutter chỉ gửi tên category gốc.

## 6. Pipeline train Model B (`train_behavior.py`)

1. Đọc `fraudTrain.csv` và `fraudTest.csv` từ `--input-dir` (đường dẫn `hdfs://` hoặc local), schema khai báo tường minh (không `inferSchema` trên 500 MB).
2. Tính feature bằng `behavior_features_spark.build_features(df)`:
   - `Window.partitionBy("cc_num").orderBy("ts", "trans_num").rowsBetween(-101, -1)` gom `collect_list` struct (amount, category, ts) của các dòng trước.
   - Lọc bằng higher-order function `filter` chỉ giữ phần tử có `ts < t`, rồi `slice` lấy 100 phần tử cuối. Mọi feature (mean, mean cùng category, đếm 24h, giao dịch gần nhất, `history_count`) tính bằng `aggregate`/`filter` trên mảng này. Cách này đảm bảo đúng định nghĩa `H` ở mục 5, kể cả khi có giao dịch trùng thời điểm, và cửa sổ cùng category nằm **trong** 100 giao dịch gần nhất như app. Lấy 101 dòng thay vì 100 để vẫn đủ 100 khi có một dòng trùng thời điểm bị loại; trường hợp nhiều dòng trùng hơn được chấp nhận là sai lệch rất nhỏ và ghi trong báo cáo.
   - `history_count` = số dòng trong cửa sổ, dùng cho cold start.
   - Train và test được nối lại theo `cc_num` trước khi tính window, để giao dịch đầu tập test có lịch sử từ cuối tập train (mô phỏng đúng thực tế), rồi tách lại theo nguồn.
3. Loại dòng `history_count < 5`.
4. Chia theo thời gian: 90% đầu `fraudTrain` là train, 10% cuối là validation (chọn threshold), toàn bộ `fraudTest` là test.
5. Xử lý mất cân bằng: cột `classWeight = n_normal / n_fraud` cho lớp fraud (tính trên tập train), giống Model A.
6. Huấn luyện và so sánh trên validation: `RandomForestClassifier` (weightCol) và `GBTClassifier` (weightCol). Chọn model có PR-AUC validation cao hơn.
7. Threshold: chọn trên validation giá trị lớn nhất của F1 cho lớp fraud. Lưu vào metadata.
8. Đánh giá trên test với threshold đã chọn: Precision, Recall, F1, PR-AUC, ROC-AUC, TP/FP/FN/TN, feature importance, số dòng bị loại vì cold start.
9. Lưu `behavior_model/` (Spark `PipelineModel`: `VectorAssembler` + classifier), `behavior_metadata.json` (danh sách feature theo thứ tự, threshold, thuật toán, version, ngày train, seed, cửa sổ lịch sử 100, ngưỡng cold start 5, bảng category), `metrics_behavior.json`.

Notebook `behavior_anomaly_pipeline.ipynb` gọi cùng các hàm trên và bổ sung EDA có biểu đồ: phân bố fraud theo giờ, theo category, phân bố `amt_ratio_user` theo nhãn, PR curve, confusion matrix, feature importance. Phần này đáp ứng tiêu chí phân tích dữ liệu và trực quan hóa.

## 7. Thí nghiệm Model A (bằng chứng số liệu)

Bổ sung vào `train_pipeline.py` (cờ `--zero-pca-ablation`) và notebook Model A: trên các dòng fraud của tập test, gán `V1..V28 = 0` và đo lại Recall. Kết quả ghi vào `metrics.json` dưới khóa `zeroPcaAblation`. Kỳ vọng Recall giảm mạnh; con số thật sẽ được báo cáo dù là bao nhiêu.

## 8. HDFS và Spark trên Docker

File mới `BigData/BigData/docker-compose.bigdata.yml`, tách khỏi `docker-compose.yml` production:

- `namenode` và `datanode` (image `apache/hadoop:3`), UI `:9870`, RPC `8020`, volume lưu dữ liệu.
- `spark-master` và `spark-worker` (image `apache/spark` chính thức), UI `:8080`, giới hạn worker khoảng 3 GB RAM (máy có 8 GB cho Docker).
- Script `scripts/hdfs_load.sh`: `hdfs dfs -mkdir -p` và `hdfs dfs -put` cho `creditcard.csv` và hai file Sparkov.
- Script `scripts/train_on_cluster.sh`: `spark-submit --master spark://spark-master:7077` chạy `train_behavior.py` và `train_pipeline.py` với input `hdfs://namenode:8020/...`; model và metrics ghi ra volume mount về `BigData/BigData/`.
- README ghi lại các lệnh và hướng dẫn chụp bằng chứng: UI HDFS (file và block), `hdfs dfs -ls -h`, Spark UI (job, stage, executor).

## 9. API v2 (`main.py`)

```
POST /v2/predict-anomaly
Request:
{
  "current": {"amount": 250000, "occurredAt": "2026-10-06T21:30:00", "category": "Ăn uống"},
  "history": [{"amount": 45000, "occurredAt": "2026-10-05T12:10:00", "category": "Ăn uống"}, ...]
}
Response:
{
  "scored": true,
  "reason": null,
  "isAnomaly": true,
  "riskScore": 0.83,
  "threshold": 0.61,
  "modelVersion": "behavior-rf-2026-10-06",
  "explanation": "Số tiền gấp 5,6 lần mức chi trung bình của bạn cho danh mục này."
}
```

- Validation (Pydantic, `extra="forbid"`): `amount` > 0 và hữu hạn; `occurredAt` là ISO 8601 không timezone (giờ địa phương); `category` chuỗi 1–100 ký tự; `history` tối đa 200 phần tử. Server tự lọc `history` chỉ lấy các phần tử có `occurredAt < current.occurredAt`, sắp xếp và cắt còn 100 gần nhất, nên app không phải tự làm.
- `scored: false` khi lịch sử hợp lệ < 5; khi đó `isAnomaly`, `riskScore`, `threshold`, `explanation` là `null`.
- `explanation` sinh theo rule từ giá trị feature (không phải SHAP): tỉ lệ số tiền cao nhất giữa `amt_ratio_user_cat` và `amt_ratio_user` nếu ≥ 3; nếu không thì `txn_count_24h` cao; nếu không thì giờ bất thường; nếu không có lý do nổi bật thì câu chung.
- `isAnomaly = riskScore >= threshold` (threshold đọc từ metadata, không dùng `prediction` mặc định 0,5).
- Model B load lúc startup cùng Model A; nếu thiếu thư mục model thì `/v2` trả 503 và `/health` báo `behaviorModelLoaded: false`. Chạy một dự đoán warm-up sau khi load, để request đầu tiên không vượt timeout 3 giây của app.
- Không log amount hay category. Log chỉ gồm loại lỗi và thời gian xử lý.
- `/predict-fraud` (Model A) giữ nguyên contract.

## 10. Flutter

File mới `mobile-app/lib/services/anomaly_check_service.dart`:

- `AnomalyCheckService.check({amount, occurredAt, category})` lấy lịch sử bằng `RemoteApiHelper.instance.fetchTransactions()` (`GET /api/transactions/mine`), giữ giao dịch chi tiêu (`isExpense`), map sang `{amount, occurredAt, category}`, gọi `FRAUD_API_URL` (`String.fromEnvironment`) bằng Dio, timeout 3 giây.
- Trả về `AnomalyResult?`; `null` cho mọi trường hợp không chấm được: chưa cấu hình URL, không ở chế độ full, `scored: false`, lỗi mạng, timeout, response sai định dạng.
- `AnomalyResult.fromJson(...)` chỉ chấp nhận response có `scored == true` và đúng kiểu.

`review_form_page.dart`:

- `_checkAnomalyAndSave` gọi service. Có `AnomalyResult` với `isAnomaly == true` thì hiện dialog (tiêu đề "Chi tiêu bất thường so với thói quen", số tiền, điểm rủi ro, câu `explanation`, nút "Hủy" / "Vẫn lưu"); còn lại lưu ngay.
- Xóa `_fraudFeaturesFromScan` (contract Model A không còn dùng trên app) và toàn bộ comment marker `/// bigdata`.
- Ngày giờ giao dịch lấy từ `_dateController` theo định dạng `dd/MM/yyyy HH:mm`, giống `_saveToDatabase`, để feature thời gian đúng với ngày trên hóa đơn.

## 11. Xử lý lỗi

| Tình huống | Hành vi |
|---|---|
| Chưa cấu hình `FRAUD_API_URL` hoặc chế độ demo | Không gọi API, lưu bình thường |
| Lấy lịch sử từ core-backend lỗi | Không gọi API, lưu bình thường |
| BigData API timeout, lỗi mạng, 4xx, 5xx | Lưu bình thường, `debugPrint` chỉ loại lỗi |
| `scored: false` (cold start) | Lưu bình thường, không hiện gì |
| `isAnomaly: false` | Lưu bình thường |
| `isAnomaly: true` | Dialog; "Vẫn lưu" thì lưu, "Hủy" thì ở lại form |
| Model B chưa load ở server | 503; app xử lý như lỗi mạng |
| Màn hình giao dịch nghi vấn: API lỗi hoặc timeout | Hiện "Chưa kiểm tra được", có nút thử lại; không crash |
| Màn hình giao dịch nghi vấn: dưới 5 giao dịch | Hiện "Cần ít nhất 5 giao dịch để phân tích thói quen" |
| Banner Trang chủ: API lỗi hoặc không có cờ | Ẩn banner |

## 12. Kiểm thử

Theo yêu cầu của người dùng (2026-10-07): **không viết unit test**, chỉ kiểm thử end-to-end trên hệ thống chạy thật.

- `scripts/e2e_check.py` gọi API đang chạy: health, validation 422, cold start, lịch sử sau ngày hóa đơn bị bỏ qua, ca bình thường, ca bất thường có giải thích, độ trễ dưới 3 giây, ánh xạ danh mục tiếng Việt, Model A trên dòng gian lận thật của Kaggle.
- **Parity replay** (thay cho parity test): job Spark xuất 30 giao dịch test (15 gian lận, 15 bình thường) kèm lịch sử thô, feature và xác suất; script gửi lại qua API và yêu cầu feature và xác suất khớp (sai số ≤ 1e-6).
- `scan-history` (mục 16): điểm của từng giao dịch trong kết quả batch phải bằng điểm khi gọi `/v2/predict-anomaly` cho riêng giao dịch đó với lịch sử trước nó.
- Flutter: `flutter analyze` không lỗi trên các file thay đổi; kiểm tra bằng tay trên app với tài khoản demo đã seed (mục 16).
- Giữ các test cũ của Model A trong `test_main.py`.

## 13. Tài liệu kiến trúc (archify)

Một sơ đồ kiến trúc HTML toàn monorepo (mobile-app, core-backend, ai-service DONUT, PostgreSQL, Redis, BigData/HDFS/Spark) và một sơ đồ data-flow cho luồng quét hóa đơn → cảnh báo. Lưu trong `docs/architecture/`. Kèm một project skill ngắn (`.claude/skills/donutpay-architecture/SKILL.md`) tóm tắt kiến trúc và trỏ tới sơ đồ, để các phiên sau chỉ cần đọc skill này.

## 14. Đối chiếu rubric

| Tiêu chí (điểm) | Bằng chứng |
|---|---|
| CLO1 Tính ứng dụng (1,0) | Cảnh báo thật trên app, dùng feature app có; thí nghiệm zero-PCA chứng minh vì sao cần Model B |
| CLO2 Lưu trữ và xử lý (2,0) | HDFS Docker thật; Spark Window functions trên 1,85 triệu dòng; class weights; EDA |
| CLO3 Mô hình/công nghệ (2,5) | Model A + Model B; so sánh RF và GBT; split theo thời gian; chọn threshold trên validation; đủ metric |
| CLO3 Trực quan hóa/ứng dụng (2,5) | Biểu đồ notebook; API v2 + Swagger; dialog có giải thích lúc lưu; màn hình "Giao dịch nghi vấn" có biểu đồ và danh sách, banner Trang chủ; sơ đồ kiến trúc |
| CLO4 Báo cáo (1,0) | Spec này, README, metrics JSON, giới hạn nêu rõ (dữ liệu mô phỏng, HDFS 1 node) |
| CLO5 Làm việc nhóm (1,0) | Ngoài phạm vi code; báo cáo cần phụ lục phân công |

## 15. Rủi ro

- **RAM Docker 8 GB:** Spark Window trên 1,85 triệu dòng với `collect_list` 100 phần tử có thể tốn bộ nhớ. Giảm thiểu: repartition theo `cc_num`, `spark.sql.shuffle.partitions` phù hợp, worker 3 GB; nếu vẫn thiếu thì chạy train bằng Spark local trên máy host đọc `hdfs://` và ghi rõ trong báo cáo.
- **Metric Model B có thể thấp** do chỉ dùng 7 feature hành vi. Kết quả thật sẽ được báo cáo; nếu thấp thì phân tích nguyên nhân thay vì thêm feature app không có.
- **Lệch phân phối Sparkov và người dùng Việt Nam** (giờ giấc, tần suất). Nêu trong phần giới hạn; feature tỉ lệ giảm bớt ảnh hưởng của đơn vị tiền tệ nhưng không loại bỏ hoàn toàn khác biệt hành vi.
- **Hóa đơn quét lại ngày cũ:** server chỉ dùng lịch sử trước `occurredAt`, nên không rò rỉ tương lai.

## 16. Bổ sung 2026-10-08: giao diện trực quan hóa giao dịch nghi vấn

Form đăng ký cam kết "API phục vụ dự đoán và Giao diện trực quan hóa các giao dịch nghi vấn được tích hợp trực tiếp dưới dạng tính năng Cảnh báo trên ứng dụng di động". Popup lúc lưu (mục 10) chưa đủ, vì chấm xong là bỏ, người dùng không xem lại được.

### 16.1 Cách làm: quét lại khi mở, không lưu điểm

Không sửa core-backend, không đổi schema PostgreSQL. Khi người dùng mở màn hình, app gửi toàn bộ giao dịch chi tiêu của họ lên BigData API; API chấm từng giao dịch dựa trên lịch sử trước nó và trả về các giao dịch bị gắn cờ.

Lý do chọn (so với lưu điểm vào core-backend lúc tạo giao dịch):

- Kết quả luôn khớp model hiện tại; retrain là tự cập nhật, không có điểm cũ lệch model.
- Chấm được cả giao dịch cũ và giao dịch nhập tay, không chỉ hóa đơn quét sau khi có tính năng.
- Không chạm Spring Boot và PostgreSQL production.

Lưu điểm vào core-backend (kèm `modelVersion`) là hướng phát triển khi cần lưu vết "đã cảnh báo lúc nào"; ghi trong báo cáo.

### 16.2 API `POST /v2/scan-history`

```
Request:
{"transactions": [{"id": "123", "amount": 45000, "occurredAt": "2026-10-05T12:10:00", "category": "Ăn uống"}, ...]}

Response:
{
  "modelVersion": "behavior-gbt-2026-10-07",
  "threshold": 0.961,
  "scannedCount": 45,
  "scoredCount": 40,
  "flaggedCount": 2,
  "flaggedByCategory": {"Mua sắm": 1, "Ăn uống": 1},
  "flagged": [{"id": "123", "amount": 1200000, "occurredAt": "...", "category": "Mua sắm",
               "riskScore": 0.98, "explanation": "Số tiền gấp 11,1 lần mức chi trung bình của bạn."}]
}
```

- `scannedCount`: số giao dịch nhận được; `scoredCount`: số đủ lịch sử để chấm; `flaggedByCategory` theo tên danh mục gốc của app.
- Validation như mục 9 (`extra="forbid"`, `amount` > 0 và hữu hạn); `transactions` tối đa 1.000 phần tử; `id` là chuỗi tùy chọn, tối đa 64 ký tự, để app nối kết quả về giao dịch.
- Mỗi giao dịch được chấm bằng `compute_features(giao_dịch, các_giao_dịch_khác)`, cùng định nghĩa mục 5 (100 giao dịch gần nhất có thời điểm nhỏ hơn hẳn; dưới 5 thì không chấm). Để tránh O(n²), sắp xếp theo thời gian một lần và mỗi lần chỉ đưa vào tối đa 200 giao dịch đứng trước.
- Mọi giao dịch đủ lịch sử được gom thành **một** DataFrame và `transform` một lần bằng `PipelineModel`, không gọi Spark từng dòng.
- `flagged` sắp xếp theo `riskScore` giảm dần. Không log amount hay category. Model B chưa load: 503.

### 16.3 Flutter

- `AnomalyCheckService.scanHistory()` trong `anomaly_check_service.dart`: lấy giao dịch qua `fetchTransactions()`, giữ chi tiêu có amount > 0, tối đa 1.000 giao dịch gần nhất, gọi `/v2/scan-history`, timeout 10 giây. Trả về `AnomalyScan?` (null khi không ở chế độ full, chưa cấu hình URL hoặc lỗi).
- **Cache trong phiên:** lưu kết quả cuối cùng cùng khóa (số giao dịch, ngày giao dịch mới nhất). Mở lại mà dữ liệu không đổi thì dùng cache; kéo xuống để làm mới thì bỏ qua cache. Lưu giao dịch mới (mục 10) xóa cache.
- **Trang `SuspiciousTransactionsPage`** (`lib/screens/ai/suspicious_transactions_page.dart`):
  - tóm tắt "X/Y giao dịch có dấu hiệu bất thường" và phiên bản model;
  - biểu đồ cột số giao dịch nghi vấn theo danh mục (`fl_chart`, app đã dùng);
  - danh sách giao dịch nghi vấn: ngày, danh mục, số tiền, thanh điểm rủi ro, câu giải thích;
  - trạng thái rỗng, lỗi (nút thử lại), chưa đủ dữ liệu như mục 11.
- **Lối vào chính:** thẻ "Cảnh báo chi tiêu bất thường" ở đầu danh sách insight của tab AI (`ai_tab_page.dart`), hiện số giao dịch nghi vấn, bấm vào mở trang.
- **Banner Trang chủ** (`dashboard_page.dart`): chỉ hiện khi `flaggedCount > 0` ("Có 2 giao dịch chi tiêu bất thường"), bấm vào mở trang. Lỗi hoặc không có cờ thì ẩn.
- Không thêm tab ở thanh điều hướng dưới: bố cục hiện tại đối xứng quanh nút Quét, và một tab riêng sẽ trống với người dùng mới (cold start). Form ghi "tích hợp trực tiếp dưới dạng tính năng", nên đặt trong luồng có sẵn khớp hơn.

### 16.4 Dữ liệu demo

Script `BigData/BigData/scripts/seed_demo_history.py`: đăng nhập tài khoản demo qua core-backend, tạo khoảng 30 giao dịch theo thói quen (ăn trưa, đi chợ cuối tuần, xăng xe) trải trên 30 ngày trước, cộng 2–3 giao dịch bất thường (số tiền lớn, đêm khuya, danh mục ít dùng). Mục đích: hội đồng mở app là thấy banner và danh sách, và quét hóa đơn mới đã đủ lịch sử để chấm. Chỉ dùng cho tài khoản demo.

### 16.5 Kiểm thử

- `e2e_check.py` thêm: `scannedCount`/`scoredCount` đúng; giao dịch bất thường chèn vào lịch sử thói quen bị gắn cờ; điểm batch của một giao dịch bằng điểm `/v2/predict-anomaly` với lịch sử trước nó; 422 khi quá 1.000 giao dịch; 1.000 giao dịch chấm xong dưới 10 giây.
- `flutter analyze` sạch; kiểm tra bằng tay trên app với tài khoản demo đã seed.
