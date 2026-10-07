# Behavior Anomaly Model (Model B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Train a Spark behavior-anomaly model on Sparkov stored in a real Docker HDFS, serve it through FastAPI `/v2/predict-anomaly`, and make the Flutter receipt flow show real model-backed warnings.

**Architecture:** Docker compose (HDFS namenode/datanode + Spark master/worker + API) under `BigData/BigData/`. Spark jobs read `hdfs://namenode:8020/data/...`, build 7 behavior features with window functions, compare RF/GBT/rule baseline, and write a `PipelineModel` plus metadata. The API recomputes the same features in pure Python; Flutter sends the current receipt plus the user's history.

**Tech Stack:** Apache Hadoop 3.4.1, Apache Spark 4.0.1 (PySpark ML), FastAPI + Pydantic v2, Flutter/Dio.

**Spec:** `docs/superpowers/specs/2026-10-06-bigdata-behavior-anomaly-design.md`

## Global Constraints

- User override (2026-10-07): no unit tests. Verification is end-to-end output checks only (HDFS listing, Spark job output, metrics files, API responses, Spark-vs-API parity replay, `flutter analyze`).
- History window: 100 most recent transactions strictly before `t`; cold start when fewer than 5.
- Time: naive local datetime converted to epoch seconds as if UTC on both sides (`spark.sql.session.timeZone=UTC`).
- Category order: the 14 Sparkov categories sorted alphabetically; index = position.
- Do not commit CSV data, virtualenvs, or Kaggle tokens.
- Flutter: fail-open on every error; never fabricate features; keep Dio.
- Docker memory budget: 8 GB total.

## Review Focus

- Receipt dated earlier than some history entries: server must drop history with `occurredAt >= current.occurredAt` (parity replay covers this because Spark uses strict `<`).
- Unknown app category names (admin-defined, Vietnamese with or without diacritics): must map to a Sparkov category, falling back to `misc_pos`.
- Duplicate or zero-amount history rows from the backend: zero/negative amounts are dropped before averaging, so no division by zero.
- First request after API start: warm-up prediction must run during startup so the 3 s Flutter timeout is not hit.
- BigData API down or model missing: Flutter saves normally with no dialog.

---

## File Structure

`BigData/BigData/`
- `docker-compose.bigdata.yml` (create): namenode, datanode, spark-master, spark-worker, fraud-api.
- `hadoop.env` (create): HDFS config via `CORE-SITE.XML_*` / `HDFS-SITE.XML_*` env vars.
- `docker/spark.Dockerfile` (create): `apache/spark:4.0.1-python3` + numpy, pandas, matplotlib, nbconvert, ipykernel.
- `scripts/hdfs_load.sh` (create): put CSVs into HDFS, print listing.
- `scripts/train_on_cluster.sh` (create): spark-submit both jobs, execute notebooks.
- `scripts/e2e_check.py` (create): end-to-end API checks plus Spark-vs-API parity replay.
- `behavior_features.py` (create): pure-Python feature definitions, category map, explanation rules. Shared contract.
- `behavior_features_spark.py` (create): Spark implementation of the same features.
- `train_behavior.py` (create): Model B training/evaluation job.
- `train_pipeline.py` (modify): explicit schema, ROC-AUC/TN, `--zero-pca-ablation`.
- `behavior_api.py` (create): `BehaviorEngine` + `/v2/predict-anomaly` router.
- `main.py` (modify): include v2 router, shared lifespan, fix Model A message.
- `Dockerfile` (modify): copy new modules and `behavior_model/`.
- `behavior_anomaly_pipeline.ipynb` (create): EDA charts from HDFS + Model B results.
- `README.md` (modify), `docs/REPORT_NOTES.md` (create): run guide, results, rubric evidence, limitations.

`mobile-app/lib/`
- `services/anomaly_check_service.dart` (create): history fetch, request build, API call, result parsing.
- `screens/receipt_scanner/review_form_page.dart` (modify): use the service, new dialog, remove `/// bigdata` markers and `_fraudFeaturesFromScan`.

Docs: `docs/architecture/` (archify HTML) and `.claude/skills/donutpay-architecture/SKILL.md`.

---

### Task 1: HDFS + Spark cluster on Docker

**Files:** create `docker-compose.bigdata.yml`, `hadoop.env`, `docker/spark.Dockerfile`, `scripts/hdfs_load.sh`.

- [ ] Write compose: `namenode` (`hdfs namenode`, `ENSURE_NAMENODE_DIR`, port 9870), `datanode` (`hdfs datanode`), `spark-master` (port 8080, 7077), `spark-worker` (`SPARK_WORKER_MEMORY=3g`, cores 4), both Spark services built from `docker/spark.Dockerfile`, with `.` bind-mounted at `/workspace`.
- [ ] `hadoop.env`: `fs.defaultFS=hdfs://namenode:8020`, `dfs.replication=1`, `dfs.permissions.enabled=false`.
- [ ] `scripts/hdfs_load.sh`: `docker compose exec namenode hdfs dfs -mkdir -p /data/sparkov /data/creditcard` + `-put -f` from a `/workspace`-mounted path, then `hdfs dfs -ls -h -R /data` and `hdfs fsck /data -files -blocks | tail`.
- [ ] Verify: `docker compose -f docker-compose.bigdata.yml up -d --build`, run the load script; expected output lists `fraudTrain.csv` (~335 MB), `fraudTest.csv` (~143 MB), `creditcard.csv` (~144 MB) and fsck `HEALTHY`.

### Task 2: Shared feature contract (`behavior_features.py`)

**Interfaces (produces):**
- `SPARKOV_CATEGORIES: list[str]` (14, sorted)
- `FEATURE_COLUMNS = ["hour", "day_of_week", "category_idx", "amt_ratio_user", "amt_ratio_user_cat", "txn_count_24h", "secs_since_prev"]`
- `HISTORY_WINDOW = 100`, `MIN_HISTORY = 5`
- `to_epoch_seconds(dt: datetime) -> float`
- `map_category(name: str) -> str`
- `@dataclass Txn(amount: float, occurred_at: datetime, category: str)`
- `compute_features(current: Txn, history: list[Txn]) -> dict[str, float] | None` (None means cold start)
- `explain(features: dict[str, float]) -> str`

- [ ] Implement exactly the spec §5 formulas; strip Vietnamese diacritics with `unicodedata` for keyword matching; exact Sparkov names map to themselves.
- [ ] Verify: `python -c` smoke call with 6 history items prints a 7-key dict; 4 items prints `None`.

### Task 3: Spark features + Model B training job

**Files:** create `behavior_features_spark.py`, `train_behavior.py`.

**Interfaces:**
- `build_features(df: DataFrame) -> DataFrame` adds `ts`, `history` (array of struct ts/amt/category), the 7 features, and `history_count`.
- CLI: `train_behavior.py --input-dir hdfs://namenode:8020/data/sparkov --model-output behavior_model --artifacts-dir artifacts/behavior`.
- Outputs: `behavior_model/` (PipelineModel: VectorAssembler -> VectorIndexer(maxCategories=14) -> classifier), `behavior_model/behavior_metadata.json`, `artifacts/behavior/metrics_behavior.json`, `artifacts/behavior/pr_curve_test.json`, `artifacts/behavior/parity_sample.json`.

- [ ] Explicit CSV schema; `ts = unix_timestamp(to_timestamp(trans_date_trans_time))`; union train/test with `source` column before the window.
- [ ] Window `partitionBy(cc_num).orderBy(ts, trans_num).rowsBetween(-101, -1)` collecting structs; filter `ts < current ts`; keep the last 100 with `slice(h, greatest(size(h) - 99, 1), 100)`; compute features with `aggregate`/`filter`/`array_max`.
- [ ] Drop `history_count < 5`; time split: fraudTrain `ts` below its 0.9 approx quantile is train, the rest is validation; fraudTest is test.
- [ ] Class weight on train; fit RF (100 trees, depth 10) and GBT (60 iterations, depth 6); pick higher validation PR-AUC.
- [ ] Threshold: aggregate validation probabilities into 1000 bins in Spark, choose the max-F1 threshold on the driver.
- [ ] Test metrics at that threshold (P/R/F1/PR-AUC/ROC-AUC/TP/FP/FN/TN), feature importances, cold-start drop counts, rule baseline (`amt_ratio_user_cat >= 3`) metrics, test PR curve from bins.
- [ ] Parity sample: 15 fraud + 15 normal test rows with their full `history` array, features and probability.
- [ ] Verify: run via `scripts/train_on_cluster.sh`; expected files exist and `metrics_behavior.json` has non-null test metrics for both candidates.

### Task 4: Model A upgrade + ablation (`train_pipeline.py`)

- [ ] Explicit schema; add ROC-AUC, TN, train/test counts; `--zero-pca-ablation` computes recall on test fraud rows with V1..V28 set to 0 and the share of all test rows flagged; write under `zeroPcaAblation`.
- [ ] Verify: rerun on HDFS input; `metrics.json` regenerated by the script with the ablation block.

### Task 5: API v2 (`behavior_api.py`, `main.py`, `Dockerfile`, compose `fraud-api`)

**Interfaces:**
- Request `{"current": {"amount", "occurredAt", "category"}, "history": [...]}` (`extra="forbid"`, history at most 200, amount > 0 finite, category 1..100 chars).
- Response `{"scored", "reason", "isAnomaly", "riskScore", "threshold", "modelVersion", "explanation", "features"}`.

- [ ] `BehaviorEngine.load()` reads the PipelineModel + metadata, validates feature order, and runs a warm-up prediction. `predict()` filters history, calls `compute_features`, builds a one-row DataFrame, and applies the threshold from metadata.
- [ ] `main.py`: one lifespan loads both engines (each failure isolated), `/health` reports both, router included; Model A message becomes neutral wording about credit-card benchmark risk.
- [ ] Dockerfile copies new modules and `behavior_model`; compose service `fraud-api` on 8001.
- [ ] Verify: `curl /health` shows both loaded; Swagger `/docs` lists `/v2/predict-anomaly`.

### Task 6: End-to-end verification (`scripts/e2e_check.py`)

- [ ] Checks: health; validation 422 (extra field, negative amount); cold start `scored=false`; normal habit `isAnomaly=false`; 10x spike `isAnomaly=true` with explanation; history-after-current is ignored; Vietnamese category mapping in `features.category_idx`; latency under 3 s; parity replay of all 30 samples (features equal within 1e-6, riskScore within 1e-6) and a confusion summary.
- [ ] Verify: `python scripts/e2e_check.py --base-url http://127.0.0.1:8001` prints PASS for every check.

### Task 7: Flutter integration

**Files:** create `mobile-app/lib/services/anomaly_check_service.dart`; modify `review_form_page.dart`.

**Interfaces:**
- `class AnomalyResult { bool isAnomaly; double riskScore; double threshold; String explanation; String modelVersion; static AnomalyResult? fromJson(Map<String, dynamic>) }`
- `class AnomalyCheckService { static Future<AnomalyResult?> check({required double amount, required DateTime occurredAt, required String category}) }`; `FRAUD_API_URL` is a base URL, path `/v2/predict-anomaly`.

- [ ] Service: return null when not full mode or URL empty; `RemoteApiHelper.instance.fetchTransactions()`, keep expenses with amount > 0, newest 100; Dio with 3 s timeouts; parse only `scored == true`.
- [ ] Page: `_checkFraudAndSave` parses the date like `_saveToDatabase`, calls the service, shows the dialog "Chi tiêu bất thường so với thói quen" with explanation, then saves or stays. Remove `_fraudFeaturesFromScan` and the markers.
- [ ] Verify: `flutter analyze lib/services/anomaly_check_service.dart lib/screens/receipt_scanner/review_form_page.dart` reports no issues.

### Task 8: Notebook, docs, architecture

- [ ] `behavior_anomaly_pipeline.ipynb`: Spark on HDFS EDA (volume, fraud rate by hour/category, `amt_ratio_user` by label), model comparison table, PR curve, confusion matrix, feature importances, ablation result; executed in the Spark container with nbconvert so outputs are saved.
- [ ] `README.md` run guide; `docs/REPORT_NOTES.md` with real numbers, rubric mapping, limitations (simulated data, single-node HDFS), defense Q&A.
- [ ] Archify diagrams in `docs/architecture/` + project skill.
- [ ] Verify: notebook has outputs; HTML diagrams open; numbers in REPORT_NOTES match the metrics JSON.

### Task 9: Review and commit

- [ ] Self-review the diff (`/code-review high` equivalent), fix findings, re-run e2e.
- [ ] Commit code, models, artifacts, docs (no CSV, no venv).
