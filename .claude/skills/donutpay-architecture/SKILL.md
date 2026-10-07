---
name: donutpay-architecture
description: Architecture map of the donutpay monorepo (Flutter app, Spring Boot core-backend, FastAPI DONUT ai-service, BigData HDFS + Spark + FastAPI anomaly models). Read this first before exploring the repo, changing a cross-service flow, or working on the Big Data coursework (Model A / Model B, HDFS, Spark, /v2/predict-anomaly).
---

# DonutPay architecture

Diagrams (open in a browser; sources are the JSON next to them, rendered with the archify skill):

- `docs/architecture/donutpay-architecture.html`: all services and how they connect.
- `docs/architecture/bigdata-dataflow.html`: dataset -> HDFS -> Spark -> models -> API -> app.

Design and plan for the Big Data work: `docs/superpowers/specs/2026-10-06-bigdata-behavior-anomaly-design.md`, `docs/superpowers/plans/2026-10-07-bigdata-behavior-anomaly.md`.

## Services

| Path | Stack | Port | Role |
|---|---|---|---|
| `mobile-app/` | Flutter, Dio, sqflite | - | Personal finance app. `AppConfig.isFull` = server mode, `isDemo` = local sqflite only. Receipt scan flow in `lib/screens/receipt_scanner/review_form_page.dart`. |
| `core-backend/` | Spring Boot (Java), JWT | 8080 | Source of truth for users, transactions, budgets, payments. PostgreSQL `finance_db`; Redis caches statistics only. `GET /api/transactions/mine` returns the user's transactions (`amount`, `date`, `category`, `isExpense`). |
| `ai-service/` | FastAPI | 8000 | DONUT receipt OCR (`POST /donut/scan`), SPADE spending analysis (`/spade/*`), admin segmentation. Called by the app (`AppConfig.mlApiUrl`) and by core-backend (`DATA_MINING_URL`). |
| `BigData/BigData/` | HDFS, Spark 4.0.1, FastAPI | 8001 | Coursework "Big Data" module. Separate compose file `docker-compose.bigdata.yml`; not part of the production `docker-compose.yml`. |

## Big Data module (`BigData/BigData/`)

Two models, on purpose:

- **Model A** (`train_pipeline.py`, `random_forest_model/`, `metrics.json`): Kaggle credit-card benchmark, 30 features `Time, V1..V28, Amount`. API `POST /predict-fraud`. Not usable by the app: receipts have no V1..V28. The `--zero-pca-ablation` experiment shows recall drops to 0 when V1..V28 = 0. Never zero-pad features in the app.
- **Model B** (`train_behavior.py`, `behavior_model/` + `behavior_metadata.json`, `artifacts/behavior/metrics_behavior.json`): Sparkov dataset (1.85M transactions), 7 behavior features the app can compute. API `POST /v2/predict-anomaly`. This is what the app calls.

Feature contract (must stay identical on both sides):

- `behavior_features.py`: pure Python definitions, category keyword map (Vietnamese app categories -> 14 Sparkov categories), explanation text. Used by the API.
- `behavior_features_spark.py`: the same features with Spark window functions. Used for training.
- History window = 100 earlier transactions (matches what the app fetches), strictly before the current time; fewer than 5 = cold start, not scored. Times are naive local datetimes read as UTC on both sides.
- If you change a feature, retrain and rerun `scripts/e2e_check.py`, which replays Spark-computed samples through the API and must match features and probabilities exactly.

Run it:

```bash
cd BigData/BigData
docker compose -f docker-compose.bigdata.yml up -d --build          # HDFS + Spark (UIs :9870, :8080)
bash scripts/hdfs_load.sh                                          # CSVs -> hdfs:///data
bash scripts/train_on_cluster.sh behavior                          # Model B
bash scripts/train_on_cluster.sh creditcard                        # Model A + ablation
docker compose -f docker-compose.bigdata.yml --profile api up -d --build fraud-api
python scripts/e2e_check.py --base-url http://127.0.0.1:8001
```

On Git Bash for Windows prefix the scripts with `MSYS_NO_PATHCONV=1`. Raw data lives in `BigData/BigData/data/` and `creditcard.csv` (gitignored; Kaggle download).

App integration: `mobile-app/lib/services/anomaly_check_service.dart` fetches history from core-backend, posts to `FRAUD_API_URL` (base URL, `--dart-define`) + `/v2/predict-anomaly`, 3 s timeout, fail-open (any error -> save normally, no dialog). The dialog title is "Chi tiêu bất thường so với thói quen".
