"""One timed run of the Model B window-feature stage on the first k scaled copies (see scale_data.py).

Stages timed separately:
  read      read the CSV or Parquet copies from HDFS and count the rows;
  features  build the 7 behavior features with the per-card window and run them to the end with the
            noop sink, so the whole window is computed without collecting anything to the driver.

Appends one JSON line to --output. A failed run (out of memory, missing input) is recorded with
status "failed" and the script still exits 0, so the benchmark loop keeps going.
"""

import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path

from pyspark.sql import SparkSession

from behavior_features import FEATURE_COLUMNS
from behavior_features_spark import build_features, read_sparkov, scorable
from scale_data import hdfs_bytes


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="hdfs://namenode:8020/data/scale")
    parser.add_argument("--format", choices=["csv", "parquet"], default="csv")
    parser.add_argument("--copies", type=int, required=True)
    parser.add_argument("--cores", type=int, required=True, help="Recorded only; spark-submit sets the real limit")
    parser.add_argument("--output", default="/workspace/artifacts/scale/results.jsonl")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    shuffle_partitions = 48 * args.copies
    result = {
        "format": args.format,
        "copies": args.copies,
        "cores": args.cores,
        "shufflePartitions": shuffle_partitions,
    }
    spark = (
        SparkSession.builder.appName(f"ScaleBenchmark-{args.format}-x{args.copies}-c{args.cores}")
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.sql.shuffle.partitions", str(shuffle_partitions))
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    started = time.time()
    try:
        paths = {
            source: [f"{args.root}/{args.format}/{source}/copy={n}" for n in range(args.copies)]
            for source in ("train", "test")
        }
        result["inputBytes"] = sum(hdfs_bytes(spark, path) for group in paths.values() for path in group)

        read_started = time.time()
        raw = read_sparkov(spark, paths["train"], "train", args.format).unionByName(
            read_sparkov(spark, paths["test"], "test", args.format)
        )
        result["rows"] = raw.count()
        result["readSeconds"] = round(time.time() - read_started, 1)

        feature_started = time.time()
        scorable(build_features(raw)).select(*FEATURE_COLUMNS).write.format("noop").mode("overwrite").save()
        result["featureSeconds"] = round(time.time() - feature_started, 1)
        result["status"] = "ok"
    except Exception as error:  # noqa: BLE001 - any failure is recorded, the loop must continue
        result["status"] = "failed"
        result["error"] = str(error)[:200]
    finally:
        result["totalSeconds"] = round(time.time() - started, 1)
        result["at"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        spark.stop()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(result) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
