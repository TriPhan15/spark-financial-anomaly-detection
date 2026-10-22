# 06. Tham chiếu API

FastAPI, cổng 8001. Code: `main.py` (app, `/health`, `/predict-fraud`, nạp mô hình) và `behavior_api.py` (router `/v2`). Swagger UI tự sinh ở http://localhost:8001/docs.

## 1. Chạy

```bash
cd BigData/BigData
docker compose -f docker-compose.bigdata.yml --profile api up -d --build fraud-api
curl http://127.0.0.1:8001/health
```

Biến môi trường (đều có giá trị mặc định, thường không cần đặt):

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `FRAUD_LOAD_MODEL` | `true` | `false` thì không mở Spark, không nạp mô hình (dùng trong `test_main.py`) |
| `BEHAVIOR_MODEL_PATH` | `./behavior_model` | Thư mục Model B |
| `FRAUD_MODEL_PATH` | `./random_forest_model` | Thư mục Model A |
| `FRAUD_SPARK_APP_NAME` | `LedgerMindFraudDetection` | Tên SparkSession local |

Khi khởi động, API mở một `SparkSession` `local[*]`, nạp từng mô hình trong một khối `try` riêng, kiểm tra metadata của Model B khớp với code, rồi chấm thử một lần để làm nóng. Mô hình nào nạp hỏng thì endpoint của nó trả 503, endpoint còn lại vẫn chạy.

## 2. Danh sách endpoint

| Method | Path | Mô hình | Ai gọi |
|---|---|---|---|
| GET | `/health` | | Người vận hành, script kiểm thử |
| POST | `/v2/predict-anomaly` | B | App, trước khi lưu một khoản chi |
| POST | `/v2/scan-history` | B | App, khi mở màn hình nghi vấn, tab AI, Trang chủ |
| POST | `/predict-fraud` | A | Chỉ dùng để demo và đối chứng |

## 3. `GET /health`

```json
{
  "status": "ok",
  "modelLoaded": true,
  "featureCount": 30,
  "behaviorModelLoaded": true,
  "behaviorModelVersion": "behavior-gbt-2026-10-07"
}
```

`modelLoaded` là Model A, `behaviorModelLoaded` là Model B. App chỉ cần cái thứ hai.

## 4. `POST /v2/predict-anomaly`

### Request

```json
{
  "current": {"amount": 1200000, "occurredAt": "2026-10-06T23:40:00", "category": "Mua sắm online"},
  "history": [
    {"amount": 48000, "occurredAt": "2026-10-05T12:10:00", "category": "Ăn uống"},
    {"amount": 51000, "occurredAt": "2026-10-04T12:10:00", "category": "Ăn uống"}
  ]
}
```

| Trường | Kiểu | Ràng buộc |
|---|---|---|
| `current` | giao dịch | bắt buộc |
| `history` | mảng giao dịch | tối đa 200 phần tử, mặc định rỗng |
| `amount` | số | lớn hơn 0, hữu hạn |
| `occurredAt` | datetime ISO 8601 | giờ địa phương; nếu có múi giờ thì phần múi giờ bị bỏ, giữ nguyên giờ đồng hồ |
| `category` | chuỗi | 1 đến 100 ký tự, tiếng Việt hoặc tên Sparkov |

Trường lạ ở bất kỳ cấp nào đều bị từ chối (`extra = "forbid"`). Thứ tự lịch sử không quan trọng, server tự sắp.

### Server làm gì

1. Ánh xạ danh mục của giao dịch hiện tại và lịch sử sang 14 danh mục Sparkov.
2. Giữ lịch sử có thời điểm trước `current.occurredAt` (nghiêm ngặt) và số tiền dương, lấy 100 gần nhất.
3. Dưới 5 thì trả `scored: false`.
4. Tính 7 feature, chấm bằng `PipelineModel`, lấy xác suất lớp gian lận làm `riskScore`.
5. `riskScore >= threshold` thì `flaggedBy = "model"`. Nếu không, mà cả `amt_ratio_user` và `amt_ratio_user_cat` từ 10 trở lên, thì `flaggedBy = "rule"`. Còn lại không gắn cờ.
6. Bị gắn cờ thì sinh câu giải thích.

### Response

| Trường | Kiểu | Ý nghĩa |
|---|---|---|
| `scored` | bool | `false` khi thiếu lịch sử |
| `reason` | chuỗi hoặc null | `"insufficient_history"` khi `scored = false` |
| `isAnomaly` | bool hoặc null | Có bị gắn cờ không (bởi mô hình hoặc luật) |
| `riskScore` | số 0 đến 1 hoặc null | Xác suất của mô hình, làm tròn 6 chữ số |
| `threshold` | số hoặc null | Ngưỡng của mô hình, 0,961 |
| `modelVersion` | chuỗi | Ví dụ `behavior-gbt-2026-10-07` |
| `explanation` | chuỗi hoặc null | Câu tiếng Việt, chỉ có khi bị gắn cờ |
| `features` | object hoặc null | 7 feature đã tính, để kiểm tra và đối chiếu |
| `flaggedBy` | `"model"`, `"rule"` hoặc null | Ai gắn cờ |

### Bốn ví dụ thật

Các file nằm trong `docs/demo/`, cùng một lịch sử 39 giao dịch (bữa trưa khoảng 45 đến 57 nghìn mỗi ngày, đi chợ 280 đến 320 nghìn cuối tuần, trong 30 ngày). Kết quả dưới đây chạy trên mô hình `behavior-gbt-2026-10-07`.

| File | Giao dịch hiện tại | Kết quả |
|---|---|---|
| `01_binh_thuong.json` | 52.000, Ăn uống, 12:15 | `isAnomaly: false`, `riskScore: 0.019442` |
| `02_mua_online_dem_khuya.json` | 1.200.000, Mua sắm online, 23:40 | `isAnomaly: true`, `riskScore: 0.978872`, `flaggedBy: "model"`, "Số tiền gấp 11,1 lần mức chi trung bình của bạn." |
| `03_khai_khong_bua_trua.json` | 5.000.000, Ăn uống, 12:15 | `isAnomaly: true`, `riskScore: 0.019388`, `flaggedBy: "rule"`, "Số tiền gấp 98,0 lần mức chi trung bình của bạn cho danh mục này." |
| `04_cold_start.json` | 5.000.000, Ăn uống, chỉ 4 giao dịch lịch sử | `scored: false`, `reason: "insufficient_history"` |

Gửi bằng curl (từ `BigData/BigData`):

```bash
curl -s -X POST http://127.0.0.1:8001/v2/predict-anomaly \
  -H "Content-Type: application/json" --data-binary @docs/demo/02_mua_online_dem_khuya.json
```

Response đầy đủ của ví dụ 2:

```json
{
  "scored": true,
  "reason": null,
  "isAnomaly": true,
  "riskScore": 0.978872,
  "threshold": 0.961,
  "modelVersion": "behavior-gbt-2026-10-07",
  "explanation": "Số tiền gấp 11,1 lần mức chi trung bình của bạn.",
  "features": {
    "hour": 23.0,
    "day_of_week": 1.0,
    "category_idx": 11.0,
    "amt_ratio_user": 11.116389548693586,
    "amt_ratio_user_cat": 11.116389548693586,
    "txn_count_24h": 0.0,
    "secs_since_prev": 127800.0
  },
  "flaggedBy": "model"
}
```

Ở ví dụ 2, lịch sử không có giao dịch `shopping_net` nào, nên `amt_ratio_user_cat` lấy bằng `amt_ratio_user` và câu giải thích dùng mẫu "mức chi trung bình của bạn". Ở ví dụ 3, điểm mô hình chỉ 0,019 (mô hình tin danh mục ăn uống), luật 10 lần mới là thứ gắn cờ.

## 5. Câu giải thích

`explain()` trong `behavior_features.py` sinh câu theo luật từ giá trị feature. Nó không đọc bên trong mô hình. Điều kiện xét theo thứ tự, điều kiện đầu tiên đúng sẽ quyết định câu:

| # | Điều kiện | Câu |
|---|---|---|
| 1 | tỉ lệ lớn hơn trong hai tỉ lệ từ 3 trở lên, và tỉ lệ danh mục lớn hơn tỉ lệ chung | "Số tiền gấp X lần mức chi trung bình của bạn cho danh mục này." |
| 1' | như trên, nhưng tỉ lệ danh mục không lớn hơn | "Số tiền gấp X lần mức chi trung bình của bạn." |
| 2 | `txn_count_24h >= 5` | "Bạn đã có N giao dịch trong 24 giờ qua, nhiều hơn thường lệ." |
| 3 | `hour < 5` | "Giao dịch vào khung giờ khuya (H giờ), khác thói quen thường ngày." |
| 4 | `secs_since_prev < 600` | "Giao dịch xảy ra ngay sau giao dịch trước đó, khác nhịp chi tiêu thường ngày." |
| 5 | còn lại | "Mẫu chi tiêu này khác với thói quen gần đây của bạn." |

X được làm tròn 1 chữ số thập phân, dùng dấu phẩy.

## 6. `POST /v2/scan-history`

Chấm toàn bộ lịch sử chi tiêu trong một request.

### Request

```json
{
  "transactions": [
    {"id": "0", "amount": 48000, "occurredAt": "2026-10-05T12:10:00", "category": "Ăn uống"},
    {"id": "odd", "amount": 1200000, "occurredAt": "2026-10-06T23:40:00", "category": "Mua sắm online"}
  ]
}
```

Tối đa 1.000 giao dịch. `id` không bắt buộc, tối đa 64 ký tự, được trả lại nguyên văn để app biết dòng nào bị gắn cờ.

### Server làm gì

1. Sắp tất cả giao dịch theo thời gian.
2. Với giao dịch thứ i, lấy tối đa 200 giao dịch đứng trước làm lịch sử rồi tính feature như `predict-anomaly` (vẫn lọc "trước nghiêm ngặt" và cửa sổ 100). Bỏ qua giao dịch chưa đủ 5 lịch sử.
3. Gom mọi dòng đủ điều kiện vào một DataFrame, chấm bằng một lần `transform`.
4. Áp ngưỡng và luật như endpoint đơn lẻ.
5. Sắp danh sách bị gắn cờ theo điểm giảm dần. Dòng do luật gắn cờ có điểm mô hình thấp, nên được xếp như thể điểm của nó bằng ngưỡng.

### Response (chạy `docs/demo/05_scan_history.json`)

```json
{
  "modelVersion": "behavior-gbt-2026-10-07",
  "threshold": 0.961,
  "scannedCount": 40,
  "scoredCount": 35,
  "flaggedCount": 1,
  "flaggedByCategory": {"Mua sắm online": 1},
  "flagged": [
    {
      "id": "odd",
      "amount": 1200000.0,
      "occurredAt": "2026-10-06T23:40:00",
      "category": "Mua sắm online",
      "riskScore": 0.978872,
      "explanation": "Số tiền gấp 11,1 lần mức chi trung bình của bạn.",
      "flaggedBy": "model"
    }
  ]
}
```

40 giao dịch được quét, 5 giao dịch đầu tiên chưa đủ lịch sử nên chỉ 35 được chấm. `flaggedByCategory` dùng tên danh mục gốc của app. Điểm của dòng `odd` trùng với điểm khi gọi `predict-anomaly` cho riêng nó, `e2e_check.py` kiểm tra điều này.

Hiệu năng đo được: 1.000 giao dịch chấm trong khoảng 1,2 giây.

## 7. `POST /predict-fraud` (Model A)

```json
{"time": 406.0, "amount": 0.0, "vFeatures": [-2.31, 1.95, "... đủ 28 số ..."]}
```

| Trường | Ràng buộc |
|---|---|
| `time` | từ 0 trở lên, hữu hạn |
| `amount` | từ 0 trở lên, hữu hạn |
| `vFeatures` | đúng 28 số hữu hạn (V1 đến V28) |

Response:

```json
{"is_fraud": true, "risk_score": 0.9713, "model_version": "random_forest_model", "message": "Giao dịch thẻ có dấu hiệu gian lận theo mô hình benchmark."}
```

File mẫu: `docs/demo/06_model_a_gian_lan_that.json` (dòng gian lận đầu tiên của bộ Kaggle, bị gắn cờ, điểm 0,9713) và `docs/demo/07_model_a_dien_0.json` (cùng dòng, V1 đến V28 bằng 0, không bị gắn cờ, điểm 0,0538).

Endpoint này dùng `prediction` của mô hình (ngưỡng mặc định của Random Forest), không dùng ngưỡng chọn trên validation như Model B.

## 8. Mã lỗi

| Mã | Khi nào | Ví dụ |
|---|---|---|
| 200 | Thành công, kể cả `scored: false` | |
| 422 | Request sai schema | trường lạ, số tiền âm hoặc bằng 0, `history` quá 200, `transactions` quá 1.000, `vFeatures` không đủ 28 |
| 503 | Mô hình chưa nạp | `"Behavior model is temporarily unavailable"` hoặc `"Fraud model is temporarily unavailable"` |

App coi mọi mã khác 200, mọi body không đúng dạng và mọi lỗi mạng là "không kiểm tra được" và lưu bình thường.

## 9. Log và quyền riêng tư

API chỉ ghi thời gian xử lý và kết quả tổng (`scored`, số quét, số chấm, số gắn cờ). Nó không ghi số tiền, danh mục hay thời điểm giao dịch. Lỗi chỉ ghi tên loại lỗi. API không lưu gì xuống đĩa và không có cơ sở dữ liệu.

API không có xác thực. Nó được thiết kế để chạy trong mạng nội bộ của môi trường demo. Đưa ra Internet thì cần thêm xác thực và giới hạn tần suất.
