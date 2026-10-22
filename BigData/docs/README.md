# Tài liệu phần Big Data

Thư mục này mô tả module phát hiện chi tiêu bất thường của DonutPay: dữ liệu lên HDFS, Spark huấn luyện mô hình, API phục vụ mô hình, và ứng dụng Flutter hiển thị cảnh báo. Mọi đường dẫn file trong các tài liệu tính từ thư mục `BigData/`, trừ khi ghi rõ là từ gốc repo.

## Đọc theo thứ tự nào

| # | Tài liệu | Nội dung | Dành cho |
|---|---|---|---|
| 1 | [01_KIEN_TRUC.md](01_KIEN_TRUC.md) | Các khối, container, cổng, thư mục, ranh giới với phần còn lại của DonutPay, các quyết định thiết kế | Ai cũng nên đọc trước |
| 2 | [02_LUONG_BIG_DATA.md](02_LUONG_BIG_DATA.md) | Luồng chính từ file CSV tới màn hình điện thoại, từng giai đoạn kèm số liệu | Người trình bày, người viết báo cáo |
| 3 | [03_HDFS_SPARK.md](03_HDFS_SPARK.md) | Cấu hình HDFS và cụm Spark, lệnh nạp dữ liệu, tham số `spark-submit`, bằng chứng cần chụp | Người phụ trách hạ tầng |
| 4 | [04_FEATURE_HANH_VI.md](04_FEATURE_HANH_VI.md) | Bảy feature hành vi, cách Spark và Python cùng tính ra một số, ánh xạ danh mục, ví dụ tính tay | Người phụ trách dữ liệu |
| 5 | [05_HUAN_LUYEN_DANH_GIA.md](05_HUAN_LUYEN_DANH_GIA.md) | Huấn luyện Model B (RF, GBT), chọn ngưỡng, kết quả test, Model A và thí nghiệm điền 0 | Người phụ trách mô hình |
| 6 | [06_API.md](06_API.md) | Tham chiếu API: endpoint, schema, mã lỗi, luật chặn thói quen, câu giải thích | Người phụ trách API, người làm app |
| 7 | [07_TICH_HOP_APP.md](07_TICH_HOP_APP.md) | App gọi API ở đâu, khi nào, xử lý lỗi ra sao, các màn hình liên quan | Người làm Flutter |
| 8 | [08_KIEM_THU_VAN_HANH.md](08_KIEM_THU_VAN_HANH.md) | 20 check đầu cuối, 44 case Flutter theo mức, cách chạy lại, xử lý sự cố | Cả nhóm trước buổi bảo vệ |
| 9 | [09_KICH_BAN_DEMO.md](09_KICH_BAN_DEMO.md) | Kịch bản quay video demo: thứ tự cảnh, thao tác, lời đọc | Người quay video |

Hai tài liệu có sẵn từ trước vẫn giữ nguyên:

- [REPORT_NOTES.md](REPORT_NOTES.md): số liệu để chép vào báo cáo Word, đối chiếu rubric, câu hỏi hội đồng hay hỏi.
- [TEAM_GUIDE.md](TEAM_GUIDE.md): bản tóm tắt cho cả nhóm, có bảng tính năng và bảng việc còn lại.

Các file payload mẫu dùng khi demo API nằm trong [demo/](demo/).

## Tóm tắt trong năm dòng

1. Dữ liệu: Sparkov (1.852.394 giao dịch thẻ mô phỏng, có nhãn gian lận) và Kaggle Credit Card (284.807 giao dịch), tổng 622,2 MB, nạp vào HDFS.
2. Xử lý: Spark 4.0.1 standalone đọc thẳng từ `hdfs://namenode:8020/data`, tính 7 feature hành vi bằng hàm cửa sổ theo từng chủ thẻ.
3. Mô hình: Model B là Gradient-Boosted Trees, PR-AUC 0,654 trên test, ngưỡng 0,961 chọn trên validation. Model A là Random Forest trên bộ Kaggle, chỉ để đối chứng.
4. Phục vụ: FastAPI cổng 8001 nạp `PipelineModel`, tính lại feature bằng đúng công thức lúc huấn luyện, trả điểm rủi ro và câu giải thích tiếng Việt.
5. Ứng dụng: trước khi lưu một khoản chi (quét hóa đơn, nhập tay, sửa), app hỏi API; nếu bất thường thì hiện hộp thoại "Chi tiêu bất thường so với thói quen". Có thêm màn hình liệt kê các giao dịch nghi vấn.

## Nguồn sự thật

Khi tài liệu và code lệch nhau, code đúng. Các con số lấy từ:

- `artifacts/behavior/metrics_behavior.json`: kết quả Model B.
- `behavior_model/behavior_metadata.json`: phiên bản mô hình, ngưỡng, danh sách feature.
- `metrics.json`: kết quả Model A và thí nghiệm điền 0.
- `scripts/e2e_check.py`: những gì API đã được kiểm chứng.
