"""Behavior features for Model B, in Spark (training side).

Implements the definitions in behavior_features.py with window functions so they run distributed
over the full Sparkov dataset. The window collects the previous rows of each card holder, then
higher-order functions keep only those strictly before the current time and the HISTORY_WINDOW most
recent ones, exactly as the API does with the history sent by the app.
"""

from pyspark.sql import DataFrame, Window, functions as F
from pyspark.sql.types import (
    DoubleType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)

from behavior_features import HISTORY_WINDOW, MIN_HISTORY, SECONDS_PER_DAY, SPARKOV_CATEGORIES

# Only the columns the app can also know about are read; personal and location columns are skipped.
SPARKOV_SCHEMA = StructType(
    [
        StructField("row_id", LongType()),
        StructField("trans_date_trans_time", StringType()),
        StructField("cc_num", LongType()),
        StructField("merchant", StringType()),
        StructField("category", StringType()),
        StructField("amt", DoubleType()),
        StructField("first", StringType()),
        StructField("last", StringType()),
        StructField("gender", StringType()),
        StructField("street", StringType()),
        StructField("city", StringType()),
        StructField("state", StringType()),
        StructField("zip", IntegerType()),
        StructField("lat", DoubleType()),
        StructField("long", DoubleType()),
        StructField("city_pop", LongType()),
        StructField("job", StringType()),
        StructField("dob", StringType()),
        StructField("trans_num", StringType()),
        StructField("unix_time", LongType()),
        StructField("merch_lat", DoubleType()),
        StructField("merch_long", DoubleType()),
        StructField("is_fraud", IntegerType()),
    ]
)


def read_sparkov(spark, path: str, source: str) -> DataFrame:
    return (
        spark.read.option("header", True)
        .schema(SPARKOV_SCHEMA)
        .csv(path)
        .select(
            "trans_date_trans_time",
            "cc_num",
            "category",
            "amt",
            "trans_num",
            F.col("is_fraud").cast("double").alias("label"),
            F.lit(source).alias("source"),
        )
        .na.drop()
    )


def build_features(df: DataFrame) -> DataFrame:
    """Adds ts, history, history_count and the 7 feature columns. Requires session time zone UTC."""
    category_index = F.create_map(
        *[item for index, name in enumerate(SPARKOV_CATEGORIES) for item in (F.lit(name), F.lit(float(index)))]
    )
    with_ts = df.withColumn(
        "local_time", F.to_timestamp("trans_date_trans_time", "yyyy-MM-dd HH:mm:ss")
    ).withColumn("ts", F.unix_timestamp("local_time").cast("double"))

    # One extra row so a previous row with the same timestamp (dropped below) still leaves 100.
    previous_rows = (
        Window.partitionBy("cc_num")
        .orderBy("ts", "trans_num")
        .rowsBetween(-(HISTORY_WINDOW + 1), -1)
    )
    collected = with_ts.withColumn(
        "previous", F.collect_list(F.struct("ts", "amt", "category")).over(previous_rows)
    )
    earlier = collected.withColumn("earlier", F.expr("filter(previous, x -> x.ts < ts)"))
    history = earlier.withColumn(
        "history",
        F.expr(f"slice(earlier, greatest(size(earlier) - {HISTORY_WINDOW - 1}, 1), {HISTORY_WINDOW})"),
    ).drop("previous", "earlier")

    same_category = "filter(history, x -> x.category = category)"
    return (
        history.withColumn("history_count", F.size("history"))
        .withColumn("hour", F.hour("local_time").cast("double"))
        .withColumn("day_of_week", ((F.dayofweek("local_time") + 5) % 7).cast("double"))
        .withColumn("category_idx", category_index[F.col("category")])
        .withColumn("user_mean", F.expr("try_divide(aggregate(history, 0D, (acc, x) -> acc + x.amt), size(history))"))
        .withColumn(
            "category_mean",
            F.expr(
                f"try_divide(aggregate({same_category}, 0D, (acc, x) -> acc + x.amt), size({same_category}))"
            ),
        )
        # Spark 4 runs in ANSI mode, so empty histories use try_divide (NULL) instead of failing;
        # those rows are cold start and are dropped by scorable().
        .withColumn("amt_ratio_user", F.try_divide(F.col("amt"), F.col("user_mean")))
        .withColumn(
            "amt_ratio_user_cat",
            F.when(F.expr(f"size({same_category})") > 0, F.try_divide(F.col("amt"), F.col("category_mean"))).otherwise(
                F.col("amt_ratio_user")
            ),
        )
        .withColumn(
            "txn_count_24h",
            F.expr(f"size(filter(history, x -> x.ts >= ts - {SECONDS_PER_DAY}))").cast("double"),
        )
        .withColumn("secs_since_prev", F.col("ts") - F.expr("array_max(transform(history, x -> x.ts))"))
        .drop("user_mean", "category_mean")
    )


def scorable(df: DataFrame) -> DataFrame:
    """Rows the API would score: at least MIN_HISTORY earlier transactions (cold start excluded)."""
    return df.filter(F.col("history_count") >= MIN_HISTORY)
