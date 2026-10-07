#!/usr/bin/env bash
# Runs the Spark training jobs on the standalone cluster, reading the raw data from HDFS.
#   scripts/train_on_cluster.sh behavior [hdfs input dir]   -> Model B (behavior anomaly)
#   scripts/train_on_cluster.sh creditcard                  -> Model A (Kaggle benchmark + ablation)
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f docker-compose.bigdata.yml"
SUBMIT=(/opt/spark/bin/spark-submit
  --master spark://spark-master:7077
  --driver-memory 2g
  --executor-memory 2560m
  --conf spark.executor.cores=4
  --conf spark.sql.session.timeZone=UTC
  --py-files /workspace/behavior_features.py,/workspace/behavior_features_spark.py)

case "${1:-behavior}" in
  behavior)
    $COMPOSE exec -T -w /workspace spark-master "${SUBMIT[@]}" /workspace/train_behavior.py \
      --input-dir "${2:-hdfs://namenode:8020/data/sparkov}" \
      --model-output "${3:-/workspace/behavior_model}" \
      --artifacts-dir "${4:-/workspace/artifacts/behavior}"
    ;;
  creditcard)
    $COMPOSE exec -T -w /workspace spark-master "${SUBMIT[@]}" /workspace/train_pipeline.py \
      --input hdfs://namenode:8020/data/creditcard/creditcard.csv \
      --model-output /workspace/random_forest_model \
      --metrics-output /workspace/metrics.json \
      --zero-pca-ablation
    ;;
  *)
    echo "usage: $0 behavior|creditcard" >&2
    exit 2
    ;;
esac
