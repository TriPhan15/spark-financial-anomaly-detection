"""Replicates Sparkov into N copies on HDFS for the scaling benchmark (timing only, not accuracy).

Copy 0 is the original data. Copy N > 0 gets a new card holder id (xxhash64 of cc_num and N) and a new
trans_num, so every copy is a separate set of users and the window per cc_num grows with the copies
instead of merging them. Output, partitioned by copy so a benchmark can read the first k copies:

  {output-root}/csv/{train,test}/copy=N/      CSV with header, same columns as the Kaggle files
  {output-root}/parquet/{train,test}/copy=N/  the same rows as Parquet

Files are written with replication 1 to keep the laptop's disk usage at about 4.5 GB for 8 copies.
"""

import argparse
import json

from pyspark.sql import SparkSession, functions as F

from behavior_features_spark import SPARKOV_SCHEMA

SOURCES = {"train": "fraudTrain.csv", "test": "fraudTest.csv"}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", default="hdfs://namenode:8020/data/sparkov")
    parser.add_argument("--output-root", default="hdfs://namenode:8020/data/scale")
    parser.add_argument("--copies", type=int, default=8)
    return parser


def hdfs_bytes(spark, path: str) -> int:
    jvm = spark.sparkContext._jvm
    hadoop_path = jvm.org.apache.hadoop.fs.Path(path)
    fs = hadoop_path.getFileSystem(spark.sparkContext._jsc.hadoopConfiguration())
    return int(fs.getContentSummary(hadoop_path).getLength())


def main() -> None:
    args = build_parser().parse_args()
    spark = (
        SparkSession.builder.appName("SparkovScaleData")
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.hadoop.dfs.replication", "1")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    try:
        rows = 0
        for source, file_name in SOURCES.items():
            raw = spark.read.option("header", True).schema(SPARKOV_SCHEMA).csv(f"{args.input_dir}/{file_name}")
            copies = [
                raw.withColumn("copy", F.lit(n))
                .withColumn("cc_num", F.col("cc_num") if n == 0 else F.xxhash64(F.col("cc_num"), F.lit(n)))
                .withColumn(
                    "trans_num", F.col("trans_num") if n == 0 else F.concat_ws("_", F.col("trans_num"), F.lit(str(n)))
                )
                for n in range(args.copies)
            ]
            scaled = copies[0]
            for frame in copies[1:]:
                scaled = scaled.unionByName(frame)
            scaled.write.mode("overwrite").option("header", True).partitionBy("copy").csv(
                f"{args.output_root}/csv/{source}"
            )
            scaled.write.mode("overwrite").partitionBy("copy").parquet(f"{args.output_root}/parquet/{source}")
            rows += spark.read.parquet(f"{args.output_root}/parquet/{source}").count()

        print(
            json.dumps(
                {
                    "copies": args.copies,
                    "rows": rows,
                    "csvBytes": hdfs_bytes(spark, f"{args.output_root}/csv"),
                    "parquetBytes": hdfs_bytes(spark, f"{args.output_root}/parquet"),
                }
            )
        )
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
