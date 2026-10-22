#!/usr/bin/env bash
# Fault-tolerance evidence for the report: with replication 2, stopping one datanode must not make the
# data unreadable. Run after hdfs_load.sh; datanode2 is started again at the end. hadoop.env shortens
# the heartbeat recheck so the namenode marks the stopped node dead after about 90 s, not 10 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f docker-compose.bigdata.yml"
FSCK_LINES="Status|Under-replicated blocks|Default replication|Average block replication|Missing blocks:"

echo "== hdfs dfsadmin -report (before)"
$COMPOSE exec -T namenode hdfs dfsadmin -report 2>/dev/null | grep -E "Live datanodes|Dead datanodes|^Name:"
echo "== hdfs fsck /data/sparkov -files -blocks (every block has Live_repl=2)"
$COMPOSE exec -T namenode hdfs fsck /data/sparkov -files -blocks 2>/dev/null | grep -E "Live_repl|$FSCK_LINES"

echo "== docker compose stop datanode2, wait 100 s for the namenode to mark it dead"
$COMPOSE stop datanode2
sleep 100

echo "== hdfs dfsadmin -report (after)"
$COMPOSE exec -T namenode hdfs dfsadmin -report 2>/dev/null | grep -E "Live datanodes|Dead datanodes|^Name:"
echo "== hdfs dfs -cat /data/sparkov/fraudTest.csv | head -n 3 (still readable with one datanode)"
$COMPOSE exec -T namenode bash -c "hdfs dfs -cat /data/sparkov/fraudTest.csv 2>/dev/null | head -n 3 | cut -c1-100"
echo "== hdfs fsck /data/sparkov (under-replicated, but no missing blocks)"
$COMPOSE exec -T namenode hdfs fsck /data/sparkov 2>/dev/null | grep -E "$FSCK_LINES"

echo "== docker compose start datanode2"
$COMPOSE start datanode2
