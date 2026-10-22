"""Model B: behavior-based spending anomaly model trained with Spark on the Sparkov dataset.

Steps (spec section 6):
  1. read fraudTrain.csv / fraudTest.csv (HDFS URI or local path) with an explicit schema;
  2. compute the 7 behavior features with window functions (behavior_features_spark.py);
  3. drop cold-start rows (fewer than MIN_HISTORY earlier transactions), as the API does;
  4. time-based split: first 90% of fraudTrain = train, last 10% = validation, fraudTest = test;
  5. class weights for the minority class, then Random Forest vs Gradient-Boosted Trees;
  6. pick the model with the best validation PR-AUC and the max-F1 threshold on validation;
  7. evaluate on test against a rule baseline and save the PipelineModel, metadata and artifacts.

Only small aggregates (probability histograms, metrics, 30 parity samples) are brought to the driver.
"""

import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path

from pyspark.ml import Pipeline
from pyspark.ml.classification import GBTClassifier, RandomForestClassifier
from pyspark.ml.evaluation import BinaryClassificationEvaluator
from pyspark.ml.feature import VectorAssembler, VectorIndexer
from pyspark.ml.functions import vector_to_array
from pyspark.sql import DataFrame, SparkSession, functions as F

from behavior_features import (
    FEATURE_COLUMNS,
    HISTORY_WINDOW,
    MIN_HISTORY,
    SPARKOV_CATEGORIES,
)
from behavior_features_spark import build_features, read_sparkov, scorable

BINS = 1000
RULE_RATIO = 3.0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", required=True, help="Folder with fraudTrain.csv and fraudTest.csv (hdfs:// or local)")
    parser.add_argument("--model-output", required=True)
    parser.add_argument("--artifacts-dir", required=True)
    parser.add_argument("--seed", type=int, default=42)
    return parser


def score_column(predictions: DataFrame) -> DataFrame:
    return predictions.withColumn("score", vector_to_array("probability")[1])


def histogram(scored: DataFrame) -> list[tuple[int, float, float]]:
    """(bin, positives, negatives) of the fraud probability in BINS buckets, computed in Spark."""
    rows = (
        scored.withColumn("bin", F.least(F.floor(F.col("score") * BINS), F.lit(BINS - 1)).cast("int"))
        .groupBy("bin")
        .agg(F.sum("label").alias("pos"), F.sum(1 - F.col("label")).alias("neg"))
        .collect()
    )
    return sorted((int(row["bin"]), float(row["pos"]), float(row["neg"])) for row in rows)


def curve_points(hist: list[tuple[int, float, float]]) -> list[dict]:
    """Precision/recall/F1 when flagging score >= bin / BINS, for every bin edge."""
    total_pos = sum(pos for _, pos, _ in hist)
    by_bin = {b: (pos, neg) for b, pos, neg in hist}
    points, tp, fp = [], 0.0, 0.0
    for b in range(BINS - 1, -1, -1):
        pos, neg = by_bin.get(b, (0.0, 0.0))
        tp, fp = tp + pos, fp + neg
        if tp + fp == 0:
            continue
        precision = tp / (tp + fp)
        recall = tp / total_pos if total_pos else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        points.append({"threshold": b / BINS, "precision": precision, "recall": recall, "f1": f1})
    return points


def confusion(df: DataFrame, flagged) -> dict:
    row = df.agg(
        F.sum(F.when(flagged & (F.col("label") == 1.0), 1).otherwise(0)).alias("tp"),
        F.sum(F.when(flagged & (F.col("label") == 0.0), 1).otherwise(0)).alias("fp"),
        F.sum(F.when(~flagged & (F.col("label") == 1.0), 1).otherwise(0)).alias("fn"),
        F.sum(F.when(~flagged & (F.col("label") == 0.0), 1).otherwise(0)).alias("tn"),
    ).first()
    tp, fp, fn, tn = (int(row[name] or 0) for name in ("tp", "fp", "fn", "tn"))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "truePositive": tp,
        "falsePositive": fp,
        "falseNegative": fn,
        "trueNegative": tn,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "flaggedShare": (tp + fp) / (tp + fp + fn + tn) if tp + fp + fn + tn else 0.0,
    }


def ranking_metrics(scored: DataFrame) -> dict:
    evaluator = BinaryClassificationEvaluator(labelCol="label", rawPredictionCol="score")
    return {
        "areaUnderPR": evaluator.setMetricName("areaUnderPR").evaluate(scored),
        "areaUnderROC": evaluator.setMetricName("areaUnderROC").evaluate(scored),
    }


def main() -> None:
    args = build_parser().parse_args()
    artifacts = Path(args.artifacts_dir)
    artifacts.mkdir(parents=True, exist_ok=True)
    started = time.time()

    spark = (
        SparkSession.builder.appName("BehaviorAnomalyTraining")
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.sql.shuffle.partitions", "48")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    try:
        raw = read_sparkov(spark, f"{args.input_dir}/fraudTrain.csv", "train").unionByName(
            read_sparkov(spark, f"{args.input_dir}/fraudTest.csv", "test")
        )
        raw_counts = {
            f"{row['source']}_{int(row['label'])}": row["count"]
            for row in raw.groupBy("source", "label").count().collect()
        }
        # The history arrays are large (up to 100 structs per row), so only the feature columns are cached.
        data = scorable(build_features(raw)).select(
            "cc_num", "trans_num", "trans_date_trans_time", "ts", "category", "amt", "label", "source",
            "history_count", *FEATURE_COLUMNS,
        ).cache()
        scorable_counts = {
            f"{row['source']}_{int(row['label'])}": row["count"]
            for row in data.groupBy("source", "label").count().collect()
        }

        train_all = data.filter(F.col("source") == "train")
        cutoff = train_all.approxQuantile("ts", [0.9], 0.0001)[0]
        train = train_all.filter(F.col("ts") < cutoff)
        validation = train_all.filter(F.col("ts") >= cutoff)
        test = data.filter(F.col("source") == "test")

        counts = train.groupBy("label").count().collect()
        by_label = {float(row["label"]): row["count"] for row in counts}
        fraud_weight = by_label[0.0] / by_label[1.0]
        train = train.withColumn(
            "classWeight", F.when(F.col("label") == 1.0, F.lit(fraud_weight)).otherwise(F.lit(1.0))
        ).cache()

        assembler = VectorAssembler(inputCols=FEATURE_COLUMNS, outputCol="raw_features")
        # category_idx (14 values) and day_of_week (7) become categorical splits; the others stay continuous.
        indexer = VectorIndexer(inputCol="raw_features", outputCol="features", maxCategories=len(SPARKOV_CATEGORIES))
        candidates = {
            "random_forest": RandomForestClassifier(
                labelCol="label", featuresCol="features", weightCol="classWeight",
                numTrees=100, maxDepth=10, maxBins=64, seed=args.seed,
            ),
            "gbt": GBTClassifier(
                labelCol="label", featuresCol="features", weightCol="classWeight",
                maxIter=60, maxDepth=6, maxBins=64, stepSize=0.1, seed=args.seed,
            ),
        }

        results, models = {}, {}
        for name, classifier in candidates.items():
            fit_started = time.time()
            model = Pipeline(stages=[assembler, indexer, classifier]).fit(train)
            validation_scored = score_column(model.transform(validation)).cache()
            validation_points = curve_points(histogram(validation_scored))
            best = max(validation_points, key=lambda point: point["f1"])
            results[name] = {
                "trainSeconds": round(time.time() - fit_started, 1),
                "validation": {**ranking_metrics(validation_scored), "bestThreshold": best},
            }
            validation_scored.unpersist()
            models[name] = model

        chosen = max(results, key=lambda name: results[name]["validation"]["areaUnderPR"])
        threshold = results[chosen]["validation"]["bestThreshold"]["threshold"]

        for name, model in models.items():
            model_threshold = results[name]["validation"]["bestThreshold"]["threshold"]
            test_scored = score_column(model.transform(test)).cache()
            results[name]["test"] = {
                **ranking_metrics(test_scored),
                "threshold": model_threshold,
                **confusion(test_scored, F.col("score") >= model_threshold),
            }
            results[name]["featureImportances"] = dict(
                zip(FEATURE_COLUMNS, [float(value) for value in model.stages[-1].featureImportances.toArray()])
            )
            if name == chosen:
                (artifacts / "pr_curve_test.json").write_text(
                    json.dumps(curve_points(histogram(test_scored)), indent=1), encoding="utf-8"
                )
                fraud = test_scored.filter(F.col("label") == 1.0).orderBy(F.rand(args.seed)).limit(15)
                normal = test_scored.filter(F.col("label") == 0.0).orderBy(F.rand(args.seed)).limit(15)
                picked = fraud.unionByName(normal).collect()
                # Recompute the history arrays only for the 30 sampled rows, for the API parity replay.
                histories = {
                    row["trans_num"]: row["history"]
                    for row in build_features(raw)
                    .filter(F.col("trans_num").isin([row["trans_num"] for row in picked]))
                    .select("trans_num", "history")
                    .collect()
                }
                samples = [
                    {
                        "transNum": row["trans_num"],
                        "label": int(row["label"]),
                        "current": {
                            "amount": row["amt"],
                            "occurredAt": row["trans_date_trans_time"].replace(" ", "T"),
                            "category": row["category"],
                        },
                        "history": [
                            {
                                "amount": item["amt"],
                                "occurredAt": datetime.fromtimestamp(item["ts"], tz=timezone.utc)
                                .strftime("%Y-%m-%dT%H:%M:%S"),
                                "category": item["category"],
                            }
                            for item in histories[row["trans_num"]]
                        ],
                        "expectedFeatures": {column: row[column] for column in FEATURE_COLUMNS},
                        "expectedScore": row["score"],
                    }
                    for row in picked
                ]
                (artifacts / "parity_sample.json").write_text(json.dumps(samples, indent=1), encoding="utf-8")
            test_scored.unpersist()

        rule_flag = F.col("amt_ratio_user_cat") >= RULE_RATIO
        baseline = confusion(test, rule_flag)

        version = f"behavior-{'rf' if chosen == 'random_forest' else 'gbt'}-{datetime.now(timezone.utc):%Y-%m-%d}"
        models[chosen].write().overwrite().save(args.model_output)
        metadata = {
            "modelVersion": version,
            "algorithm": chosen,
            "featureColumns": FEATURE_COLUMNS,
            "threshold": threshold,
            "historyWindow": HISTORY_WINDOW,
            "minHistory": MIN_HISTORY,
            "categories": SPARKOV_CATEGORIES,
            "dataset": "Sparkov credit card transactions (Kaggle kartik2112/fraud-detection)",
            "trainedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "sparkVersion": spark.version,
            "seed": args.seed,
        }
        (Path(args.model_output) / "behavior_metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")

        metrics = {
            "modelVersion": version,
            "chosenAlgorithm": chosen,
            "input": args.input_dir,
            "rowsRead": raw_counts,
            "rowsScorable": scorable_counts,
            "coldStartDropped": sum(raw_counts.values()) - sum(scorable_counts.values()),
            "split": {
                "validationCutoff": datetime.fromtimestamp(cutoff, tz=timezone.utc).isoformat(),
                "trainRows": sum(by_label.values()),
                "trainFraud": by_label[1.0],
                "validationRows": validation.count(),
                "testRows": test.count(),
            },
            "classWeightForFraud": fraud_weight,
            "candidates": results,
            "ruleBaseline": {"rule": f"amt_ratio_user_cat >= {RULE_RATIO}", **baseline},
            "elapsedSeconds": round(time.time() - started, 1),
            "sparkVersion": spark.version,
            "master": spark.sparkContext.master,
        }
        (artifacts / "metrics_behavior.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
        print(json.dumps({"chosen": chosen, "threshold": threshold, "test": results[chosen]["test"]}, indent=2))
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
