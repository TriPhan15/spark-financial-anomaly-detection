# 03. HDFS và cụm Spark

## 1. Yêu cầu máy

- Docker Desktop, cấp cho Docker khoảng 8 GB RAM. Worker xin 3 GB, driver 2 GB, namenode và datanode thêm một ít.
- Khoảng 2 GB đĩa trống cho dữ liệu (CSV trên máy cộng bản trên HDFS) và image.
- Trên Windows dùng Git Bash để chạy script `.sh`, và thêm `MSYS_NO_PATHCONV=1` trước lệnh. Không có biến này, Git Bash đổi `/data/sparkov` thành đường dẫn Windows và lệnh `hdfs` báo lỗi.

## 2. Khởi động

Chạy từ `BigData/`:

```bash
docker compose -f docker-compose.bigdata.yml up -d --build
docker compose -f docker-compose.bigdata.yml ps
```

Lệnh `up` không kèm `--profile api` chỉ bật bốn service: `namenode`, `datanode`, `spark-master`, `spark-worker`. Lần đầu sẽ build image `bigdata-spark:4.0.1`.

Kiểm tra:

| Địa chỉ | Phải thấy |
|---|---|
| http://localhost:9870 | Trang namenode, mục Datanodes có 1 node "In service" |
| http://localhost:8080 | Spark master, mục Workers có 1 worker, 4 core, 3.0 GiB |

## 3. Nạp dữ liệu: `scripts/hdfs_load.sh`

```bash
MSYS_NO_PATHCONV=1 bash scripts/hdfs_load.sh
```

Script làm các bước sau, tất cả qua `docker compose exec -T namenode`:

| Bước | Lệnh | Mục đích |
|---|---|---|
| 1 | `hdfs dfsadmin -safemode wait` | Namenode mới khởi động ở chế độ an toàn, chưa cho ghi. Chờ nó thoát. |
| 2 | `hdfs dfs -mkdir -p /data/sparkov /data/creditcard` | Tạo thư mục đích |
| 3 | `hdfs dfs -put -f /staging/sparkov/fraudTrain.csv /staging/sparkov/fraudTest.csv /data/sparkov/` | Chép Sparkov. `-f` cho phép chạy lại nhiều lần. |
| 4 | `hdfs dfs -put -f /staging/creditcard/creditcard.csv /data/creditcard/` | Chép bộ Kaggle Credit Card |
| 5 | `hdfs dfs -ls -h -R /data` | In danh sách file và kích thước |
| 6 | `hdfs dfs -du -s -h /data` | In tổng dung lượng (622.2 M) |
| 7 | `hdfs fsck /data -files -blocks` | In danh sách block và kết luận HEALTHY |

`/staging/...` là thư mục được compose mount từ máy vào container namenode, chế độ chỉ đọc. Dữ liệu đi từ ổ đĩa máy vào namenode client, rồi được ghi thành block trên datanode.

Lưu ý: compose không khai báo volume cho dữ liệu HDFS. `docker compose stop` rồi `start` thì dữ liệu còn. `docker compose down` xóa container và mất dữ liệu HDFS, khi đó phải chạy lại `hdfs_load.sh`.

### Bằng chứng cần chụp

- Output của `hdfs_load.sh`, đặc biệt dòng `622.2 M` và dòng `HEALTHY`.
- UI 9870, Utilities > Browse the file system > `/data/sparkov`: thấy hai file, cột Block Size 128 MB, Replication 1.
- Bấm vào `fraudTrain.csv` để thấy danh sách block (335 MB nên có 3 block).

## 4. Cụm Spark

| Thành phần | Cấu hình |
|---|---|
| Master | `spark://spark-master:7077`, UI cổng 8080 |
| Worker | 4 core, 3 GB (`SPARK_WORKER_CORES`, `SPARK_WORKER_MEMORY`) |
| Thư mục làm việc | Thư mục dự án mount vào `/workspace` ở cả master và worker. Job đọc code từ đây và ghi mô hình về đây, nên kết quả hiện ngay trên máy. |
| Phiên bản | Spark 4.0.1, Python 3 trong image chính thức |

### Tham số `spark-submit` (trong `scripts/train_on_cluster.sh`)

| Tham số | Giá trị | Lý do |
|---|---|---|
| `--master` | `spark://spark-master:7077` | Chạy trên cụm, không chạy local |
| `--driver-memory` | `2g` | Driver giữ histogram, ma trận nhầm lẫn và 30 mẫu, không cần nhiều |
| `--executor-memory` | `2560m` | Vừa trong 3 GB của worker |
| `spark.executor.cores` | `4` | Dùng hết 4 core của worker |
| `spark.sql.session.timeZone` | `UTC` | Thời gian trong CSV được đọc nguyên văn, khớp với API |
| `--py-files` | `behavior_features.py`, `behavior_features_spark.py` | Executor cũng cần import hai module này |

Trong code, job đặt thêm `spark.sql.shuffle.partitions = 48`.

### Hai chế độ của script

```bash
MSYS_NO_PATHCONV=1 bash scripts/train_on_cluster.sh behavior     # Model B, khoảng 6 phút
MSYS_NO_PATHCONV=1 bash scripts/train_on_cluster.sh creditcard   # Model A + thí nghiệm điền 0
```

Chế độ `behavior` nhận thêm ba tham số tùy chọn theo thứ tự: thư mục input trên HDFS, nơi ghi mô hình, nơi ghi artifact. Mặc định là `hdfs://namenode:8020/data/sparkov`, `/workspace/behavior_model`, `/workspace/artifacts/behavior`.

### Xem job trên UI

- http://localhost:8080: mục Running Applications có `BehaviorAnomalyTraining` khi job đang chạy, Completed Applications sau khi xong.
- http://localhost:4040: UI của application đang chạy. Tab Jobs và Stages cho thấy các bước đọc HDFS, shuffle theo `cc_num` của hàm cửa sổ, các vòng huấn luyện GBT. Tab Storage cho thấy DataFrame feature đang được cache.

## 5. Những gì chạy trên cụm và những gì về driver

| Việc | Chạy ở |
|---|---|
| Đọc 1,85 triệu dòng CSV từ HDFS | Executor |
| Hàm cửa sổ theo chủ thẻ, 7 feature | Executor (có shuffle theo `cc_num`) |
| Đếm dòng theo `source`, `label` | Executor, chỉ kết quả đếm về driver |
| `approxQuantile` tìm mốc chia | Executor |
| Huấn luyện RF và GBT (MLlib) | Executor |
| Histogram 1.000 bin của điểm số | Executor (`groupBy`), 1.000 dòng về driver |
| Ma trận nhầm lẫn, PR-AUC, ROC-AUC | Executor, vài con số về driver |
| Chọn ngưỡng theo F1 | Driver, trên 1.000 dòng histogram |
| Lấy 30 mẫu đối chiếu | Executor lọc, 30 dòng về driver |
| Ghi mô hình | Driver điều phối, ghi vào `/workspace` |

Không có bước nào gom toàn bộ dữ liệu về Python. Đây là điểm cần nói khi hội đồng hỏi "Big Data ở đâu".

## 6. Chạy notebook trên cụm

Notebook đã được chạy sẵn và lưu output trong git. Muốn chạy lại:

```bash
docker compose -f docker-compose.bigdata.yml exec -T -w /workspace -e HOME=/tmp spark-master \
  python3 -m jupyter nbconvert --to notebook --execute --inplace behavior_anomaly_pipeline.ipynb
```

Notebook đọc lại dữ liệu từ HDFS, dùng chính `build_features` của job huấn luyện, và đọc số liệu mô hình từ `artifacts/behavior/metrics_behavior.json` và `metrics.json`. Nó ghi 5 biểu đồ vào `artifacts/charts/`.

## 7. Tắt

```bash
docker compose -f docker-compose.bigdata.yml stop spark-master spark-worker   # nhả cổng 8080 cho core-backend
docker compose -f docker-compose.bigdata.yml stop                             # tắt hết, giữ dữ liệu HDFS
docker compose -f docker-compose.bigdata.yml down                             # xóa container, mất dữ liệu HDFS
```

## 8. Sự cố hay gặp

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `hdfs dfs -put` báo `No such file` với đường dẫn kiểu `C:/Program Files/Git/data/...` | Git Bash đổi đường dẫn | Thêm `MSYS_NO_PATHCONV=1` |
| `Name node is in safe mode` | Namenode vừa khởi động | Chạy lại, script đã có bước `safemode wait` |
| Script báo `$'\r': command not found` | File `.sh` bị đổi sang CRLF | `.gitattributes` của thư mục ép `*.sh` dùng LF. Checkout lại file. |
| API không nạp được mô hình, log có `ChecksumException` | File mô hình bị đổi dòng kết thúc khi checkout | `.gitattributes` ở gốc repo đánh dấu `behavior_model/**` và `random_forest_model/**` là `-text`. Checkout lại hai thư mục. |
| Spark master không lên, cổng 8080 bị chiếm | core-backend đang chạy | Tắt core-backend khi huấn luyện, hoặc tắt Spark khi chạy app |
| Job chết vì thiếu bộ nhớ | Docker được cấp ít RAM | Tăng RAM cho Docker Desktop lên khoảng 8 GB |
