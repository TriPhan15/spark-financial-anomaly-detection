# Ghi chú cho báo cáo và buổi bảo vệ

Tài liệu này gom toàn bộ số liệu thật (lấy từ các file kết quả trong repo) để đưa vào báo cáo Word và slide. Mọi con số đều chạy lại được bằng các lệnh trong `README.md`.

## 1. Tóm tắt

- **Đề tài:** Phát hiện giao dịch tài chính bất thường (tích hợp tính năng cảnh báo rủi ro cho hệ thống quản lý tài chính cá nhân).
- **Lưu trữ:** HDFS chạy thật trên Docker (namenode + datanode), 622 MB dữ liệu gốc ở `hdfs://namenode:8020/data/`.
- **Xử lý:** Spark 4.0.1 standalone (master + worker), đọc thẳng từ HDFS.
- **Hai mô hình:**
  - Model A (benchmark Kaggle Credit Card) dùng để đánh giá kỹ thuật xử lý mất cân bằng.
  - Model B (Sparkov, 1,85 triệu giao dịch) dùng feature hành vi mà app thật sự có. App gọi Model B, nên cảnh báo trên app là kết quả suy luận thật, không giả lập dữ liệu.

## 2. Dữ liệu

| Dataset | File | Số dòng | Gian lận | Tỉ lệ | Người dùng |
|---|---|---|---|---|---|
| Sparkov (Model B) | fraudTrain.csv (335 MB) | 1.296.675 | 7.506 | 0,58% | 983 |
| Sparkov (Model B) | fraudTest.csv (143 MB) | 555.719 | 2.145 | 0,39% | 924 |
| Kaggle Credit Card (Model A) | creditcard.csv (144 MB) | 284.807 | 492 | 0,17% | - |

Bằng chứng HDFS (chụp màn hình cho báo cáo):

- `bash scripts/hdfs_load.sh` in ra `hdfs dfs -ls -h -R /data`, dung lượng `622.2 M` và kết luận `The filesystem under path '/data' is HEALTHY`.
- UI namenode http://localhost:9870, mục Utilities > Browse the file system > `/data/sparkov`, cho thấy các block 128 MB.
- Spark UI http://localhost:8080 hiện worker 4 core và các application đã chạy.

## 3. Feature hành vi (Model B)

Mỗi giao dịch được so với tối đa 100 giao dịch trước đó của chính người đó. Spark tính bằng `Window.partitionBy("cc_num").orderBy("ts").rowsBetween(-101, -1)` kết hợp higher-order functions (`filter`, `aggregate`, `slice`).

| Feature | Ý nghĩa | Trung vị (gian lận) | Trung vị (bình thường) |
|---|---|---|---|
| `hour` | giờ giao dịch | 22 | 14 |
| `amt_ratio_user` | số tiền / trung bình của người đó | 4,94 | 0,67 |
| `amt_ratio_user_cat` | số tiền / trung bình cùng danh mục | 2,87 | 0,88 |
| `txn_count_24h` | số giao dịch trong 24 giờ trước | 4 | 3 |
| `secs_since_prev` | số giây từ giao dịch trước | 4.881 | 15.721 |
| `day_of_week`, `category_idx` | thứ, danh mục | | |

Giải thích vì sao dùng tỉ lệ thay vì số tiền: tỉ lệ không phụ thuộc đơn vị tiền tệ (USD trong Sparkov, VND trong app) và đo đúng điều đề tài cần, là "chi tiêu vượt ngưỡng so với thói quen".

## 4. Kết quả Model B (`artifacts/behavior/metrics_behavior.json`)

Chia dữ liệu theo thời gian, không xáo trộn:

- train: 1.162.460 dòng (6.333 gian lận), trước 2020-05-02;
- validation: 129.300 dòng, dùng để chọn ngưỡng;
- test: 555.639 dòng (toàn bộ `fraudTest`, giai đoạn sau);
- 4.995 dòng bị loại vì cold start (dưới 5 giao dịch trước đó).

Xử lý mất cân bằng bằng class weight 182,6 cho lớp gian lận. Kết quả trên test:

| Mô hình | PR-AUC | ROC-AUC | Ngưỡng | Precision | Recall | F1 | Thời gian train |
|---|---|---|---|---|---|---|---|
| **GBT (được chọn)** | **0,654** | **0,990** | 0,961 | **0,591** | **0,671** | **0,629** | 124 s |
| Random Forest | 0,616 | 0,990 | 0,943 | 0,610 | 0,608 | 0,609 | 150 s |
| Luật: số tiền >= 3 lần TB cùng danh mục | - | - | - | 0,027 | 0,492 | 0,051 | - |

- GBT được chọn vì có PR-AUC trên validation cao hơn (0,754 so với 0,700). Model không được chọn dựa trên test, để tránh rò rỉ.
- Ma trận nhầm lẫn của GBT trên test: TP 1.385, FP 957, FN 680, TN 552.617.
- Chỉ 0,42% giao dịch bị cảnh báo, nên người dùng không bị làm phiền liên tục. Trong các cảnh báo, 59% là gian lận thật.
- So với luật đơn giản, F1 tăng hơn 12 lần.
- Feature quan trọng nhất (GBT): `amt_ratio_user` 0,39, `category_idx` 0,23, `hour` 0,17.
- Toàn bộ job (đọc HDFS, window, train 2 mô hình, đánh giá) chạy 370 giây trên cụm.

Biểu đồ (từ notebook `behavior_anomaly_pipeline.ipynb`, đã chạy và lưu output):

- `artifacts/charts/1_fraud_by_hour_category.png`
- `artifacts/charts/2_amount_distribution.png`
- `artifacts/charts/3_fraud_by_amount_ratio.png`
- `artifacts/charts/4_model_b_pr_confusion_importance.png`
- `artifacts/charts/5_model_a_zero_pca_ablation.png`

## 5. Kết quả Model A và thí nghiệm điền 0 (`metrics.json`)

Model A là Random Forest (100 cây, độ sâu 5) với class weight 577,9, chia train/test ngẫu nhiên 80/20 (seed 42), đọc từ HDFS. Kết quả trên test (57.015 dòng, 112 gian lận):

- Precision 0,365, Recall 0,795, F1 0,500, PR-AUC 0,689, ROC-AUC 0,980.
- Feature quan trọng: V14, V10, V12, V4, V11.

**Thí nghiệm điền 0:** giữ nguyên các dòng test, thay V1..V28 bằng 0. Đây đúng là dữ liệu một app chỉ biết số tiền và thời gian sẽ phải gửi. Kết quả: Model A phát hiện **0/112** giao dịch gian lận (Recall 0) và không cảnh báo giao dịch nào. Đây là bằng chứng số liệu cho việc không dùng Model A trên hóa đơn, và cho thấy lập luận "zero-imputation" là sai.

## 6. Tích hợp ứng dụng

Luồng khi người dùng bấm Lưu hóa đơn (`review_form_page.dart` gọi `AnomalyCheckService`):

1. App lấy lịch sử chi tiêu từ core-backend (`GET /api/transactions/mine`), tối đa 100 giao dịch.
2. App gửi hóa đơn hiện tại cùng lịch sử lên `POST /v2/predict-anomaly`.
3. API ánh xạ danh mục tiếng Việt sang danh mục Sparkov, tính 7 feature bằng đúng công thức của Spark, rồi chấm điểm bằng model GBT.
4. Nếu bất thường, app hiện hộp thoại "Chi tiêu bất thường so với thói quen", kèm lý do (ví dụ "Số tiền gấp 11,1 lần mức chi trung bình của bạn.") và điểm rủi ro. Người dùng chọn "Hủy" hoặc "Vẫn lưu".
5. Người dùng mới (dưới 5 giao dịch), lỗi mạng hoặc timeout 3 giây: lưu bình thường, không chặn người dùng.

Kiểm thử end-to-end (`scripts/e2e_check.py`, 13/13 PASS):

- Model A gắn cờ 20/20 dòng gian lận thật đầu tiên của Kaggle.
- API từ chối request sai định dạng (422).
- Cold start không chấm điểm; lịch sử sau ngày hóa đơn bị bỏ qua.
- Bữa trưa bình thường: rủi ro 0,019, không cảnh báo.
- Mua online lúc 23h40, gấp khoảng 20 lần mức chi thường ngày: rủi ro 0,979, có cảnh báo kèm giải thích.
- Độ trễ khoảng 450 ms, dưới timeout 3 giây.
- **Replay 30 giao dịch test** do Spark xuất ra: feature và xác suất do API tính khớp tuyệt đối với Spark. Nghĩa là mô hình chạy trên app giống hệt mô hình đã đánh giá.

## 7. Đối chiếu rubric

| Tiêu chí (điểm) | Bằng chứng trong repo |
|---|---|
| CLO1 Tính ứng dụng (1,0) | Cảnh báo thật trên app với feature app có; thí nghiệm điền 0 chứng minh vì sao cần Model B; câu giải thích dễ hiểu cho người dùng |
| CLO2 Lưu trữ và xử lý (2,0) | HDFS thật (`hdfs_load.sh`, fsck HEALTHY); Spark đọc `hdfs://`; Window functions trên 1,85 triệu dòng; class weights; EDA bằng groupBy trên cụm |
| CLO3 Mô hình/công nghệ (2,5) | 2 bài toán, 3 cách tiếp cận (RF, GBT, luật); chia theo thời gian; chọn ngưỡng trên validation; đủ Precision, Recall, F1, PR-AUC, ROC-AUC, confusion matrix, feature importance |
| CLO3 Trực quan hóa/ứng dụng (2,5) | Notebook có 5 biểu đồ; Swagger `/docs`; dialog trên app; 2 sơ đồ kiến trúc tương tác (`docs/architecture/*.html`) |
| CLO4 Trình bày báo cáo (1,0) | Tài liệu này, README, spec thiết kế, số liệu chạy lại được |
| CLO5 Làm việc nhóm (1,0) | Nhóm tự bổ sung phụ lục phân công (Spark, dữ liệu, API, Flutter) |

## 8. Giới hạn (nên nêu chủ động trong báo cáo)

- Sparkov là dữ liệu **mô phỏng**, vì dữ liệu chi tiêu cá nhân có nhãn không được công khai. Thói quen của người dùng Việt Nam có thể khác.
- Nhãn "fraud" của Sparkov được diễn giải trên app là "chi tiêu lệch khỏi thói quen". Hóa đơn do chính người dùng quét hiếm khi là gian lận theo nghĩa đen.
- HDFS có 1 datanode (replication 1) vì chạy trên một máy. Kiến trúc giữ nguyên khi thêm datanode.
- Ánh xạ danh mục tiếng Việt sang Sparkov dùng từ khóa; danh mục không khớp được xếp vào `misc_pos`.

## 9. Câu hỏi hội đồng có thể hỏi

- **"Sao không dùng Model A cho app?"** Vì app không có V1..V28. Thí nghiệm điền 0 cho Recall bằng 0 (mục 5).
- **"Dữ liệu mô phỏng thì có ý nghĩa gì?"** Pipeline, feature và cách đánh giá giữ nguyên khi có dữ liệu thật. Mô phỏng là lựa chọn duy nhất có nhãn ở quy mô lớn, và nhóm nêu rõ giới hạn này.
- **"Sao biết feature trên app giống lúc train?"** `e2e_check.py` replay 30 giao dịch test qua API; feature và xác suất khớp tuyệt đối với Spark.
- **"Sao ngưỡng là 0,961 mà không phải 0,5?"** Class weight làm xác suất lệch lên. Ngưỡng được chọn trên validation để F1 lớn nhất, không chọn trên test.
- **"Sao chia theo thời gian?"** Chia ngẫu nhiên sẽ dùng giao dịch tương lai để đoán quá khứ, cho kết quả lạc quan hơn thực tế.
- **"Big Data ở đâu?"** HDFS lưu dữ liệu. Spark xử lý phân tán: window theo từng chủ thẻ trên 1,85 triệu dòng, MLlib train trên 1,16 triệu dòng. Chỉ số liệu tổng hợp nhỏ được đưa về driver.

## 10. Kịch bản demo

1. `docker compose -f docker-compose.bigdata.yml up -d`, rồi mở http://localhost:9870 (file trên HDFS) và http://localhost:8080 (Spark).
2. Mở notebook đã chạy, đi qua EDA, bảng so sánh mô hình, thí nghiệm điền 0.
3. Swagger http://localhost:8001/docs: gửi một request `/v2/predict-anomaly` bình thường và một request bất thường.
4. Chạy `python scripts/e2e_check.py` để cho thấy 13/13 PASS và parity với Spark.
5. Trên điện thoại (app build với `--dart-define=FRAUD_API_URL=http://<IP máy>:8001`): quét một hóa đơn lớn bất thường và cho thấy hộp thoại cảnh báo kèm lý do.
