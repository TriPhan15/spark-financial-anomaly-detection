#!/usr/bin/env bash
# Copies the raw CSV files into HDFS and prints evidence (listing + block report) for the report.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f docker-compose.bigdata.yml"

$COMPOSE exec -T namenode hdfs dfsadmin -safemode wait
$COMPOSE exec -T namenode hdfs dfs -mkdir -p /data/sparkov /data/creditcard
$COMPOSE exec -T namenode hdfs dfs -put -f /staging/sparkov/fraudTrain.csv /staging/sparkov/fraudTest.csv /data/sparkov/
$COMPOSE exec -T namenode hdfs dfs -put -f /staging/creditcard/creditcard.csv /data/creditcard/

echo "== hdfs dfs -ls -h -R /data"
$COMPOSE exec -T namenode hdfs dfs -ls -h -R /data
echo "== hdfs dfs -du -s -h /data"
$COMPOSE exec -T namenode hdfs dfs -du -s -h /data
echo "== hdfs fsck /data"
$COMPOSE exec -T namenode hdfs fsck /data -files -blocks | tail -n 20
