#!/usr/bin/env bash
# Fault-tolerance evidence for the report: with replication 2, stopping one datanode must not make the
# data unreadable. Run after hdfs_load.sh; datanode2 is started again at the end.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f docker-compose.bigdata.yml"

echo "== hdfs dfsadmin -report (live datanodes)"
$COMPOSE exec -T namenode hdfs dfsadmin -report | grep -E "Live datanodes|^Name:"
echo "== hdfs fsck /data/sparkov -files -blocks -locations (2 replicas per block)"
$COMPOSE exec -T namenode hdfs fsck /data/sparkov -files -blocks -locations | tail -n 15

echo "== docker compose stop datanode2"
$COMPOSE stop datanode2
sleep 15

echo "== hdfs dfs -cat /data/sparkov/fraudTest.csv | head -n 3 (still readable with one datanode)"
$COMPOSE exec -T namenode bash -c "hdfs dfs -cat /data/sparkov/fraudTest.csv | head -n 3"
echo "== hdfs dfs -count /data/sparkov"
$COMPOSE exec -T namenode hdfs dfs -count /data/sparkov
echo "== hdfs fsck /data/sparkov"
$COMPOSE exec -T namenode hdfs fsck /data/sparkov | tail -n 12

echo "== docker compose start datanode2"
$COMPOSE start datanode2
