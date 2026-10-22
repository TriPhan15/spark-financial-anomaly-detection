#!/usr/bin/env bash
# Runs the scaling benchmark matrix on the Spark cluster (needs `train_on_cluster.sh scale` first).
# Every run appends one JSON line to artifacts/scale/results.jsonl; a failed run is recorded and the
# loop goes on. About 60-90 minutes in total on the 4-core worker.
set -uo pipefail
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f docker-compose.bigdata.yml"

# The Spark containers run as another uid, so the host folder must be writable for them.
mkdir -p artifacts/scale
chmod 777 artifacts/scale

runs=0
bench() {
  local format=$1 copies=$2 cores=$3
  echo "== $format, $copies copies, $cores cores"
  $COMPOSE exec -T -w /workspace spark-master /opt/spark/bin/spark-submit \
    --master spark://spark-master:7077 \
    --driver-memory 2g \
    --executor-memory 2560m \
    --total-executor-cores "$cores" \
    --conf spark.executor.cores="$cores" \
    --conf spark.sql.session.timeZone=UTC \
    --py-files /workspace/behavior_features.py,/workspace/behavior_features_spark.py,/workspace/scale_data.py \
    /workspace/benchmark_scale.py --format "$format" --copies "$copies" --cores "$cores" \
    || echo "run failed to start: $format x$copies c$cores"
  runs=$((runs + 1))
}

for copies in 1 2 4 8; do bench csv "$copies" 4; done      # Volume scaling
for copies in 1 2 4 8; do bench parquet "$copies" 4; done  # CSV vs Parquet
for cores in 1 2; do bench csv 4 "$cores"; done            # core scaling (the 4-core point is above)

echo "done: $runs runs, see artifacts/scale/results.jsonl"
