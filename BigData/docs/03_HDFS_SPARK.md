# 03. HDFS và cụm Spark

## 1. Yêu cầu máy

- Docker Desktop, cấp cho Docker khoảng 8 GB RAM. Worker xin 3 GB, driver 2 GB, namenode và hai datanode thêm một ít.
- Khoảng 2 GB đĩa trống cho dữ liệu chính (622 MB CSV trên máy cộng khoảng 1,2 GB trên HDFS vì replication 2), cộng phần cho image. Thí nghiệm mở rộng ở mục 4 cần thêm khoảng 5,3 GB (CSV 4,08 GB và Parquet 1,20 GB, ghi với replication 1).
- Trên Windows dùng Git Bash để chạy script `.sh`, và thêm `MSYS_NO_PATHCONV=1` trước lệnh. Không có biến này, Git Bash đổi `/data/sparkov` thành đường dẫn Windows và lệnh `hdfs` báo lỗi.

## 2. Khởi động

Chạy từ `BigData/`:

```bash
docker compose -f docker-compose.bigdata.yml up -d --build
docker compose -f docker-compose.bigdata.yml ps
```

Lệnh `up` không kèm `--profile api` bật năm service: `namenode`, `datanode`, `datanode2`, `spark-master`, `spark-worker`. Chạy `bash scripts/preflight.sh` trước để chắc chắn ba file CSV có trong `data/`. Lần đầu sẽ build image `bigdata-spark:4.0.1`.

Kiểm tra:

| Địa chỉ | Phải thấy |
|---|---|
| http://localhost:9870 | Trang namenode, mục Datanodes có 2 node "In service" |
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
| 6 | `hdfs dfs -du -s -h /data` | In tổng dung lượng: cột đầu là kích thước dữ liệu (622.2 M), cột thứ hai là dung lượng tính cả hai bản sao |
| 7 | `hdfs fsck /data -files -blocks` | In danh sách block và kết luận HEALTHY |

`/staging/...` là thư mục được compose mount từ máy vào container namenode, chế độ chỉ đọc. Lệnh `hdfs dfs -put` chạy với vai trò HDFS client bên trong container namenode: client đọc file từ `/staging`, xin namenode cấp block, rồi ghi từng block theo pipeline lên hai datanode. Dữ liệu không nằm trên namenode, namenode chỉ giữ metadata (file gồm những block nào, block nằm ở datanode nào).

Lưu ý: dữ liệu HDFS nằm trong các named volume `hdfs-name`, `hdfs-data1`, `hdfs-data2`, nên `stop`/`start` và cả `down` đều giữ dữ liệu. Chỉ `docker compose down -v` mới xóa volume, khi đó phải chạy lại `hdfs_load.sh`. Lần đầu nâng cấp lên phiên bản có volume này cũng phải chạy lại `hdfs_load.sh` một lần.

### Bằng chứng cần chụp

- Output của `hdfs_load.sh`, đặc biệt dòng `622.2 M` và dòng `HEALTHY`.
- UI 9870, Utilities > Browse the file system > `/data/sparkov`: thấy hai file, cột Block Size 128 MB, Replication 2.
- Bấm vào `fraudTrain.csv` để thấy danh sách block (335 MB nên có 3 block: 128 + 128 + 79 MB), mỗi block có bản trên cả `datanode` và `datanode2`.

### Chịu lỗi: `scripts/hdfs_fault_demo.sh`

HDFS có 2 datanode, replication 2: mỗi block nằm trên cả hai node. Script tắt `datanode2`, chờ khoảng 100 giây (`hadoop.env` đặt `dfs.namenode.heartbeat.recheck-interval=30000` ; thời gian để namenode coi một node là chết bằng 2 × recheck-interval + 10 × heartbeat interval (3 giây), tức 2 × 30 + 10 × 3 = 90 giây, thay vì khoảng 10,5 phút với recheck-interval mặc định 5 phút), rồi kiểm tra:

- `dfsadmin -report`: `Live datanodes (1)`, `Dead datanodes (1)`;
- `hdfs dfs -cat` vẫn đọc được file;
- `fsck`: `Status: HEALTHY`, `Under-replicated blocks` là toàn bộ số block (100%), `Missing blocks: 0`.

Trong lúc chỉ còn một datanode, namenode không tạo được bản thứ hai vì không còn node nào khác để đặt. Cuối cùng script bật lại `datanode2`; node này gửi block report, namenode ghi nhận lại các bản sao trên đó và số block under-replicated về 0.

Các container HDFS chạy bằng root vì Docker tạo volume thuộc quyền root, còn user `hadoop` (uid 1000) trong image không ghi được.

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
| `spark.executor.cores` | `4` | Dùng hết 4 core của worker, nên cụm chạy một executor 4 core |
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

### Thí nghiệm mở rộng

```bash
bash scripts/train_on_cluster.sh scale                         # nhân Sparkov 8 bản vào /data/scale (CSV + Parquet, replication 1)
bash scripts/run_scale_benchmark.sh 2>&1 | tee scale_run.log   # 10 lần chạy, khoảng 75 phút
docker compose -f docker-compose.bigdata.yml exec -T -u "$(id -u):$(id -g)" -e MPLCONFIGDIR=/tmp -w /workspace \
  spark-master python3 scripts/plot_scale.py                   # 3 biểu đồ 6-8 và bảng Markdown
```

Kết quả và nhận xét: `REPORT_NOTES.md` mục 7b.

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
| Ghi mô hình | Driver điều phối, executor ghi các file của `PipelineModel` (JSON, Parquet) vào `/workspace`. Ghi được vì master và worker mount cùng thư mục dự án. `behavior_metadata.json` và các file số liệu do driver ghi bằng Python. |

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
docker compose -f docker-compose.bigdata.yml down                             # xóa container, dữ liệu HDFS vẫn còn trong volume
docker compose -f docker-compose.bigdata.yml down -v                          # xóa luôn volume, mất dữ liệu HDFS
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
