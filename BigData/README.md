# Big Data: spending anomaly detection

Coursework module for "Phát hiện giao dịch tài chính bất thường (tích hợp tính năng cảnh báo rủi ro cho hệ thống quản lý tài chính cá nhân)". It runs on its own Docker stack (HDFS + Spark + API) and does not change `core-backend` or `ai-service`. The mobile app calls its API before saving a scanned receipt.

Diagrams: `docs/architecture/`. Report notes with all numbers: `docs/REPORT_NOTES.md`.

HDFS now runs two datanodes with replication 2 and keeps its data in the named volumes `hdfs-name`, `hdfs-data1` and `hdfs-data2`, so `docker compose down` no longer loses it (`down -v` does). The volumes are new in this version: after upgrading, run `hdfs_load.sh` once more.

## Two models

| | Model A (benchmark) | Model B (used by the app) |
|---|---|---|
| Data | Kaggle `mlg-ulb/creditcardfraud`, 284,807 rows | Kaggle `kartik2112/fraud-detection` (Sparkov), 1,852,394 rows |
| Features | `Time, V1..V28, Amount` (anonymized bank PCA) | 7 behavior features computed from the user's own history |
| Training | `train_pipeline.py`, Random Forest + class weights | `train_behavior.py`, window features, RF vs GBT, time split |
| Model | `random_forest_model/`, `metrics.json` | `behavior_model/`, `artifacts/behavior/metrics_behavior.json` |
| API | `POST /predict-fraud` | `POST /v2/predict-anomaly` |

Model A cannot score receipts: the app has no V1..V28. `train_pipeline.py --zero-pca-ablation` measures what zero-filling them would do (recall falls from 0.795 to 0), which is why the app uses Model B.

## Run everything

Prerequisites: Docker with about 8 GB of memory, the CSV files in `data/fraudTrain.csv`, `data/fraudTest.csv` and `data/creditcard.csv` (Kaggle downloads, not committed). On Git Bash for Windows, prefix the scripts with `MSYS_NO_PATHCONV=1`.

```bash
cd BigData
bash scripts/preflight.sh                                    # stops if a Kaggle CSV is missing
docker compose -f docker-compose.bigdata.yml up -d --build   # namenode, datanode, datanode2, spark-master, spark-worker
bash scripts/hdfs_load.sh                                   # put the CSVs into hdfs:///data, print listing + fsck
bash scripts/hdfs_fault_demo.sh                             # stop datanode2, data still readable (replication 2)
bash scripts/train_on_cluster.sh behavior                   # Model B (about 6 minutes)
bash scripts/train_on_cluster.sh creditcard                 # Model A + zero-PCA ablation
docker compose -f docker-compose.bigdata.yml exec -T -w /workspace -e HOME=/tmp spark-master \
  python3 -m jupyter nbconvert --to notebook --execute --inplace behavior_anomaly_pipeline.ipynb
docker compose -f docker-compose.bigdata.yml --profile api up -d --build fraud-api
python scripts/e2e_check.py --base-url http://127.0.0.1:8001
```

Web UIs: HDFS http://localhost:9870, Spark http://localhost:8080, Swagger http://localhost:8001/docs.

## Model B API

```http
POST /v2/predict-anomaly
{
  "current": {"amount": 1200000, "occurredAt": "2026-10-06T23:40:00", "category": "Mua sắm online"},
  "history": [{"amount": 45000, "occurredAt": "2026-10-05T12:10:00", "category": "Ăn uống"}]
}
```

`history` holds up to 200 earlier transactions of the user. The server keeps those strictly before `occurredAt`, takes the 100 most recent, and computes the features with `behavior_features.py`, the same definitions as the Spark job (`behavior_features_spark.py`). With fewer than 5 it answers `{"scored": false, "reason": "insufficient_history"}`. Otherwise it returns `isAnomaly`, `riskScore`, the `threshold` chosen on the validation split, a Vietnamese `explanation` and the `features`.

## Mobile app

Build the app with `--dart-define=FRAUD_API_URL=http://<host>:8001` (base URL, no path). `lib/services/anomaly_check_service.dart` fetches the user's history from core-backend and calls the API with a 3 s timeout. Any failure, a timeout, or `scored: false` saves the receipt normally; an anomaly shows the "Chi tiêu bất thường so với thói quen" dialog.

## Verification

`scripts/e2e_check.py` checks the running API end to end: request validation, cold start, history after the receipt date, a normal and an anomalous case, latency under 3 s, Model A on real Kaggle fraud rows, and a replay of 30 test transactions exported by the Spark job, whose features and probabilities must match the API exactly.

## Limitations

Sparkov is simulated data: labelled personal spending data is not public. HDFS has two datanodes (replication 2) and Spark one worker, but they all run on one physical machine. The 14.8-million-row scaling test uses replicated copies and measures time only. In the app, the Sparkov "fraud" label is presented as spending that departs from the user's habits.

## Scaling benchmark

Timing only: the copies are replicated Sparkov rows with new card ids, never used for accuracy.

```bash
bash scripts/train_on_cluster.sh scale        # 8 copies on HDFS as CSV and Parquet (replication 1), prints sizes
bash scripts/run_scale_benchmark.sh 2>&1 | tee scale_run.log   # 10 runs, appends artifacts/scale/results.jsonl
```
