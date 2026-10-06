# Thiết kế: Cảnh báo chi tiêu bất thường dựa trên hành vi (Model B) cho đồ án Big Data

- Ngày: 2026-10-06
- Phạm vi: `BigData/BigData/` (Spark + FastAPI), `mobile-app/lib/screens/receipt_scanner/review_form_page.dart`, tài liệu kiến trúc (archify)
- Trạng thái: chờ review

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

- `AnomalyCheckService.check({amount, occurredAt, category})` lấy lịch sử bằng `RemoteApiHelper.instance.getTransactions()`, giữ giao dịch chi tiêu (`isExpense`), map sang `{amount, occurredAt, category}`, gọi `FRAUD_API_URL` (`String.fromEnvironment`) bằng Dio, timeout 3 giây.
- Trả về `AnomalyResult?`; `null` cho mọi trường hợp không chấm được: chưa cấu hình URL, không ở chế độ full, `scored: false`, lỗi mạng, timeout, response sai định dạng.
- Hàm thuần `buildAnomalyRequest(...)` và `AnomalyResult.fromJson(...)` tách riêng để unit test.

`review_form_page.dart`:

- `_checkFraudAndSave` gọi service. Có `AnomalyResult` với `isAnomaly == true` thì hiện dialog (tiêu đề "Chi tiêu bất thường so với thói quen", số tiền, điểm rủi ro, câu `explanation`, nút "Hủy" / "Vẫn lưu"); còn lại lưu ngay.
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

## 12. Kiểm thử

Python (`pytest`):

- `test_behavior_features.py`: unit test hàm Python, gồm biên cửa sổ 24h, cold start đúng 4 và 5 giao dịch, lịch sử có ngày tương lai bị loại, cắt còn 100, category chưa có lịch sử, ánh xạ keyword có dấu và không dấu.
- **Parity test** (`test_feature_parity.py`, đánh dấu `spark`): fixture khoảng 50 giao dịch của 3 user, tính bằng Spark local và bằng Python, so từng feature với sai số ≤ 1e-9.
- `test_main.py`: validation request v2, `scored: false`, threshold dùng từ metadata, 503 khi model chưa load (stub engine). Giữ các test hiện có của Model A.
- Smoke test với model thật (đánh dấu `slow`): một request v2 trả về trong dưới 3 giây sau warm-up.

Flutter (`flutter test`):

- `test/services/anomaly_check_service_test.dart`: `buildAnomalyRequest` (lọc chi tiêu, định dạng ngày), `AnomalyResult.fromJson` (thiếu trường, sai kiểu, `scored: false`).

## 13. Tài liệu kiến trúc (archify)

Một sơ đồ kiến trúc HTML toàn monorepo (mobile-app, core-backend, ai-service DONUT, PostgreSQL, Redis, BigData/HDFS/Spark) và một sơ đồ data-flow cho luồng quét hóa đơn → cảnh báo. Lưu trong `docs/architecture/`. Kèm một project skill ngắn (`.claude/skills/donutpay-architecture/SKILL.md`) tóm tắt kiến trúc và trỏ tới sơ đồ, để các phiên sau chỉ cần đọc skill này.

## 14. Đối chiếu rubric

| Tiêu chí (điểm) | Bằng chứng |
|---|---|
| CLO1 Tính ứng dụng (1,0) | Cảnh báo thật trên app, dùng feature app có; thí nghiệm zero-PCA chứng minh vì sao cần Model B |
| CLO2 Lưu trữ và xử lý (2,0) | HDFS Docker thật; Spark Window functions trên 1,85 triệu dòng; class weights; EDA |
| CLO3 Mô hình/công nghệ (2,5) | Model A + Model B; so sánh RF và GBT; split theo thời gian; chọn threshold trên validation; đủ metric |
| CLO3 Trực quan hóa/ứng dụng (2,5) | Biểu đồ notebook; API v2 + Swagger; dialog có giải thích trên app; sơ đồ kiến trúc |
| CLO4 Báo cáo (1,0) | Spec này, README, metrics JSON, giới hạn nêu rõ (dữ liệu mô phỏng, HDFS 1 node) |
| CLO5 Làm việc nhóm (1,0) | Ngoài phạm vi code; báo cáo cần phụ lục phân công |

## 15. Rủi ro

- **RAM Docker 8 GB:** Spark Window trên 1,85 triệu dòng với `collect_list` 100 phần tử có thể tốn bộ nhớ. Giảm thiểu: repartition theo `cc_num`, `spark.sql.shuffle.partitions` phù hợp, worker 3 GB; nếu vẫn thiếu thì chạy train bằng Spark local trên máy host đọc `hdfs://` và ghi rõ trong báo cáo.
- **Metric Model B có thể thấp** do chỉ dùng 7 feature hành vi. Kết quả thật sẽ được báo cáo; nếu thấp thì phân tích nguyên nhân thay vì thêm feature app không có.
- **Lệch phân phối Sparkov và người dùng Việt Nam** (giờ giấc, tần suất). Nêu trong phần giới hạn; feature tỉ lệ giảm bớt ảnh hưởng của đơn vị tiền tệ nhưng không loại bỏ hoàn toàn khác biệt hành vi.
- **Hóa đơn quét lại ngày cũ:** server chỉ dùng lịch sử trước `occurredAt`, nên không rò rỉ tương lai.
