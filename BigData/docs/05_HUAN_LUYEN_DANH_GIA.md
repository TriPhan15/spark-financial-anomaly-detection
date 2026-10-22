# 05. Huấn luyện và đánh giá

Hai mô hình, hai vai trò. Model B là mô hình app dùng. Model A là chuẩn đối chứng, và là bằng chứng vì sao không thể dùng mô hình kiểu ngân hàng cho app.

| | Model A | Model B |
|---|---|---|
| Vai trò | Benchmark, đối chứng | Chạy trong app |
| Dữ liệu | Kaggle Credit Card, 284.807 dòng | Sparkov, 1.852.394 dòng |
| Feature | 30: `Time`, `V1..V28` (PCA của ngân hàng), `Amount` | 7 feature hành vi (xem `04_FEATURE_HANH_VI.md`) |
| Thuật toán | Random Forest | Gradient-Boosted Trees (thắng Random Forest trên validation) |
| Chia dữ liệu | Ngẫu nhiên 80/20, seed 42 | Theo thời gian: train, validation, test |
| Script | `train_pipeline.py` | `train_behavior.py` |
| Kết quả | `metrics.json` | `artifacts/behavior/metrics_behavior.json` |
| Endpoint | `POST /predict-fraud` | `POST /v2/predict-anomaly`, `POST /v2/scan-history` |

Không so sánh trực tiếp chỉ số của A với B. Dữ liệu, feature và tập test đều khác nhau.

## 1. Model B

### 1.1 Dữ liệu qua từng bước

| Bước | Bình thường | Gian lận | Tổng |
|---|---|---|---|
| Đọc từ `fraudTrain.csv` | 1.289.169 | 7.506 | 1.296.675 |
| Đọc từ `fraudTest.csv` | 553.574 | 2.145 | 555.719 |
| Sau khi bỏ cold start, phần train | 1.284.641 | 7.119 | 1.291.760 |
| Sau khi bỏ cold start, phần test | 553.574 | 2.065 | 555.639 |
| Train (trước 2020-05-02 14:52:07) | | 6.333 | 1.162.460 |
| Validation (từ mốc đó, phần còn lại của file train) | | | 129.300 |
| Test (toàn bộ file test đủ lịch sử) | | 2.065 | 555.639 |

4.995 dòng bị bỏ vì có dưới 5 giao dịch trước đó: 4.528 dòng bình thường và 387 dòng gian lận ở file train (vài giao dịch đầu tiên của mỗi chủ thẻ), cộng 80 dòng gian lận ở file test (chủ thẻ mới xuất hiện và bị gian lận ngay). API cũng không chấm những trường hợp như vậy, nên huấn luyện bỏ chúng là đúng với lúc dùng.

### 1.2 Mất cân bằng

Gian lận chiếm khoảng 0,5% train. Accuracy vô nghĩa: đoán "bình thường" cho mọi dòng vẫn đạt 99,5%. Vì vậy:

- Huấn luyện với `weightCol`: dòng gian lận nặng 182,56 (bằng 1.156.127 / 6.333), dòng bình thường nặng 1.
- Đánh giá bằng PR-AUC, Precision, Recall, F1 của lớp gian lận, không dùng accuracy.

### 1.3 Hai ứng viên

| | Random Forest | GBT |
|---|---|---|
| Tham số | `numTrees=100`, `maxDepth=10`, `maxBins=64`, `seed=42` | `maxIter=60`, `maxDepth=6`, `maxBins=64`, `stepSize=0.1`, `seed=42` |
| Pipeline | `VectorAssembler` → `VectorIndexer(maxCategories=14)` → bộ phân loại | như bên trái |
| Thời gian huấn luyện | 150,2 giây | 123,7 giây |

### 1.4 Chọn mô hình và ngưỡng (chỉ dùng validation)

Ngưỡng 0,5 không dùng được: class weight 182 đẩy xác suất của mọi dòng lên cao. Job chọn ngưỡng như sau:

1. Chia điểm số validation thành 1.000 bin trên Spark, đếm số gian lận và bình thường mỗi bin.
2. Trên driver, với mỗi ngưỡng `k / 1000`, tính Precision, Recall, F1 khi gắn cờ mọi dòng có điểm từ ngưỡng đó trở lên.
3. Lấy ngưỡng có F1 lớn nhất.

| Trên validation | PR-AUC | ROC-AUC | Ngưỡng tốt nhất | Precision | Recall | F1 |
|---|---|---|---|---|---|---|
| Random Forest | 0,700 | 0,989 | 0,943 | 0,716 | 0,644 | 0,678 |
| **GBT** | **0,754** | **0,991** | **0,961** | 0,717 | 0,700 | **0,708** |

GBT có PR-AUC validation cao hơn nên được chọn. Ngưỡng 0,961 ghi vào `behavior_metadata.json`, API đọc từ đó.

### 1.5 Kết quả trên test

| Phương pháp | PR-AUC | ROC-AUC | Ngưỡng | TP | FP | FN | TN | Precision | Recall | F1 | Bị cảnh báo |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **GBT** | **0,654** | 0,990 | 0,961 | 1.385 | 957 | 680 | 552.617 | 0,591 | **0,671** | **0,629** | 0,42% |
| Random Forest | 0,616 | 0,990 | 0,943 | 1.255 | 804 | 810 | 552.770 | 0,610 | 0,608 | 0,609 | 0,37% |
| Luật `amt_ratio_user_cat >= 3` | | | | 1.015 | 36.993 | 1.050 | 516.581 | 0,027 | 0,492 | 0,051 | 6,84% |

Đọc kết quả bằng lời:

- Cứ 1.000 giao dịch thì GBT cảnh báo khoảng 4. Trong các cảnh báo, khoảng 59% là gian lận thật.
- GBT bắt được 67% gian lận, bỏ sót 33%.
- Tỉ lệ gian lận trong test khoảng 0,37%, nên một bộ phân loại đoán ngẫu nhiên có PR-AUC cỡ 0,004. PR-AUC 0,654 cao hơn khoảng 170 lần, nhưng vẫn là mức vừa phải, không phải hoàn hảo.
- Luật "gấp 3 lần trung bình cùng danh mục" bắt được 49% gian lận nhưng cảnh báo 6,84% giao dịch, hầu hết là nhầm. F1 của GBT cao hơn hơn 12 lần.
- ROC-AUC của hai mô hình gần như bằng nhau (0,990). Với dữ liệu lệch lớp nặng, ROC-AUC không phân biệt được, nên PR-AUC mới là thước đo chính.

### 1.6 Feature importance (GBT)

| Feature | Độ quan trọng |
|---|---|
| `amt_ratio_user` | 0,392 |
| `category_idx` | 0,226 |
| `hour` | 0,170 |
| `secs_since_prev` | 0,076 |
| `amt_ratio_user_cat` | 0,074 |
| `txn_count_24h` | 0,050 |
| `day_of_week` | 0,012 |

Ba feature hàng đầu khớp với phân tích khám phá: gian lận trong Sparkov là các khoản lớn so với thói quen, ở một số danh mục mua sắm và tạp hóa, vào ban đêm.

### 1.7 Điểm yếu đã biết và luật chặn thói quen

Sparkov gần như không có gian lận ở danh mục ăn uống. Trên test chỉ có 4 dòng `food_dining` có tỉ lệ từ 10 trở lên, cả 4 đều bình thường. Mô hình học được rằng một khoản lớn ở danh mục ăn uống là bình thường, nên cho điểm khoảng 0,02 cho cả bữa trưa 5 triệu.

API bổ sung một luật (`HABIT_GUARD_RATIO = 10` trong `behavior_features.py`): nếu mô hình không gắn cờ nhưng `amt_ratio_user >= 10` và `amt_ratio_user_cat >= 10`, giao dịch vẫn bị gắn cờ, với `flaggedBy = "rule"`. Mô hình và số liệu không thay đổi. Ngưỡng 10 do nhóm chọn và chưa được kiểm chứng trên dữ liệu người dùng thật.

### 1.8 Thời gian và tài nguyên

Toàn bộ job (đọc HDFS, hàm cửa sổ, huấn luyện hai mô hình, đánh giá, xuất artifact) chạy 370,3 giây trên cụm `spark://spark-master:7077`, một worker 4 core, Spark 4.0.1.

## 2. Model A và thí nghiệm điền 0

### 2.1 Huấn luyện

`train_pipeline.py`, chạy bằng `bash scripts/train_on_cluster.sh creditcard`:

- Đọc `hdfs://namenode:8020/data/creditcard/creditcard.csv` với schema khai báo sẵn (31 cột kiểu double).
- Class weight cho lớp gian lận: 284.315 / 492 = 577,9.
- Chia ngẫu nhiên 80/20, seed 42: 227.792 dòng train, 57.015 dòng test (112 gian lận).
- Random Forest 100 cây, sâu tối đa 5.

### 2.2 Kết quả trên test

| Precision | Recall | F1 | PR-AUC | ROC-AUC | TP | FP | FN | TN |
|---|---|---|---|---|---|---|---|---|
| 0,365 | 0,795 | 0,500 | 0,689 | 0,980 | 89 | 155 | 23 | 56.748 |

Feature quan trọng nhất: V14 (0,269), V10 (0,191), V12 (0,099), V4 (0,097), V11 (0,075). Toàn là biến PCA. `Amount` chỉ 0,006, `Time` 0,001.

### 2.3 Thí nghiệm điền 0

Câu hỏi: nếu app chỉ biết số tiền và thời gian, điền 0 cho V1 đến V28 rồi gọi Model A thì sao?

Cách làm: giữ nguyên 57.015 dòng test, thay V1 đến V28 bằng 0, chấm lại bằng cùng mô hình.

| | Đủ 30 feature | V1..V28 = 0 |
|---|---|---|
| Gian lận bắt được | 89/112 | 0/112 |
| Recall | 0,795 | 0 |
| Giao dịch bị cảnh báo | 244 | 0 |

Mô hình không cảnh báo giao dịch nào. Giá trị 0 nằm giữa phân bố của các thành phần PCA, mô hình đọc nó là "rất bình thường". Đây là bằng chứng số liệu cho hai điều: không được điền 0 cho feature thiếu, và app cần một mô hình riêng dựa trên dữ liệu nó thật sự có. Đó là Model B.

Có thể xem trực tiếp trên API bằng hai file mẫu: `docs/demo/06_model_a_gian_lan_that.json` (một dòng gian lận thật, điểm 0,971, bị gắn cờ) và `docs/demo/07_model_a_dien_0.json` (cùng dòng đó với V = 0, điểm 0,054, không bị gắn cờ).

## 3. Biểu đồ

Notebook `behavior_anomaly_pipeline.ipynb` (đã chạy, lưu output) xuất 5 hình vào `artifacts/charts/`:

| File | Nội dung |
|---|---|
| `1_fraud_by_hour_category.png` | Tỉ lệ gian lận theo giờ và theo danh mục |
| `2_amount_distribution.png` | Phân bố số tiền (thang log) của hai lớp |
| `3_fraud_by_amount_ratio.png` | Tỉ lệ gian lận theo mức lệch so với thói quen cùng danh mục |
| `4_model_b_pr_confusion_importance.png` | Đường PR trên test, ma trận nhầm lẫn, feature importance của GBT |
| `5_model_a_zero_pca_ablation.png` | Model A đủ feature so với điền 0 |

Các phép `groupBy` của notebook chạy trên Spark, chỉ bảng tổng hợp được chuyển sang pandas để vẽ.

## 4. Huấn luyện lại

```bash
cd BigData/BigData
docker compose -f docker-compose.bigdata.yml up -d --build
MSYS_NO_PATHCONV=1 bash scripts/hdfs_load.sh
MSYS_NO_PATHCONV=1 bash scripts/train_on_cluster.sh behavior
MSYS_NO_PATHCONV=1 bash scripts/train_on_cluster.sh creditcard
docker compose -f docker-compose.bigdata.yml --profile api up -d --build fraud-api
python -X utf8 scripts/e2e_check.py --base-url http://127.0.0.1:8001
```

Huấn luyện lại sẽ ghi đè `behavior_model/`, `behavior_metadata.json` (phiên bản mới theo ngày), các file số liệu và `parity_sample.json`. Seed cố định là 42, nhưng thứ tự xử lý phân tán có thể làm số liệu lệch rất nhỏ so với bản trong tài liệu.
