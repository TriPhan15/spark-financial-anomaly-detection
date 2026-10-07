"""Model A: reproducible Spark fraud-training pipeline on the Kaggle credit-card benchmark.

The dataset contains Time, V1..V28, Amount and Class. The script uses Spark class weights instead of
collecting or duplicating the dataset in Python, then writes a Spark ML Random Forest model and JSON
metrics. With --zero-pca-ablation it also measures what happens when V1..V28 are replaced by 0, which
is what an app that only knows the amount and the time would have to send. That experiment is the
evidence for not using this model on receipts (Model B is used in the app instead).
"""

import argparse
import json
from pathlib import Path

from pyspark.ml.classification import RandomForestClassifier
from pyspark.ml.evaluation import BinaryClassificationEvaluator
from pyspark.ml.feature import VectorAssembler
from pyspark.sql import DataFrame, SparkSession, functions as F
from pyspark.sql.types import DoubleType, StructField, StructType

PCA_COLUMNS = [f"V{i}" for i in range(1, 29)]
FEATURE_COLUMNS = ["Time", *PCA_COLUMNS, "Amount"]
SCHEMA = StructType([StructField(column, DoubleType()) for column in [*FEATURE_COLUMNS, "Class"]])


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="CSV path or HDFS URI")
    parser.add_argument("--model-output", required=True)
    parser.add_argument("--metrics-output", required=True)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--zero-pca-ablation", action="store_true")
    return parser


def confusion(predictions: DataFrame) -> dict:
    counts = predictions.agg(
        *[
            F.sum(F.when((F.col("Class") == label) & (F.col("prediction") == predicted), 1).otherwise(0)).alias(name)
            for name, label, predicted in (("tp", 1.0, 1.0), ("fp", 0.0, 1.0), ("fn", 1.0, 0.0), ("tn", 0.0, 0.0))
        ]
    ).first()
    tp, fp, fn, tn = (int(counts[name] or 0) for name in ("tp", "fp", "fn", "tn"))
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
    }


def main() -> None:
    args = build_parser().parse_args()
    output = Path(args.metrics_output)
    output.parent.mkdir(parents=True, exist_ok=True)

    spark = SparkSession.builder.appName("LedgerMindFraudTraining").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    try:
        # An explicit schema avoids a second pass over the file that inferSchema would need.
        data = spark.read.option("header", True).schema(SCHEMA).csv(args.input).na.drop()
        fraud_count = data.filter(F.col("Class") == 1.0).count()
        normal_count = data.filter(F.col("Class") == 0.0).count()
        if fraud_count == 0 or normal_count == 0:
            raise ValueError("Both Class=0 and Class=1 are required")

        # Weight the minority class without collecting the full dataset in Python.
        fraud_weight = normal_count / fraud_count
        weighted = data.withColumn(
            "classWeight",
            F.when(F.col("Class") == 1.0, F.lit(fraud_weight)).otherwise(F.lit(1.0)),
        )
        train, test = weighted.randomSplit([0.8, 0.2], seed=args.seed)
        assembler = VectorAssembler(inputCols=FEATURE_COLUMNS, outputCol="features")

        classifier = RandomForestClassifier(
            labelCol="Class",
            featuresCol="features",
            weightCol="classWeight",
            numTrees=100,
            maxDepth=5,
            seed=args.seed,
        )
        model = classifier.fit(assembler.transform(train))
        test = test.cache()
        predictions = model.transform(assembler.transform(test)).cache()

        evaluator = BinaryClassificationEvaluator(labelCol="Class", rawPredictionCol="rawPrediction")
        metrics = {
            "dataset": "Kaggle mlg-ulb/creditcardfraud",
            "input": args.input,
            "featureColumns": FEATURE_COLUMNS,
            "seed": args.seed,
            "trainCount": train.count(),
            "testCount": test.count(),
            "normalCount": normal_count,
            "fraudCount": fraud_count,
            "classWeightForFraud": fraud_weight,
            **confusion(predictions),
            "areaUnderPR": evaluator.setMetricName("areaUnderPR").evaluate(predictions),
            "areaUnderROC": evaluator.setMetricName("areaUnderROC").evaluate(predictions),
            "featureImportances": dict(zip(FEATURE_COLUMNS, map(float, model.featureImportances.toArray()))),
            "sparkVersion": spark.version,
        }

        if args.zero_pca_ablation:
            # Same test rows, but V1..V28 = 0: only Time and Amount keep their real values.
            zeroed = test.select(
                "Time", *[F.lit(0.0).alias(column) for column in PCA_COLUMNS], "Amount", "Class"
            )
            ablated = model.transform(assembler.transform(zeroed)).cache()
            ablation = confusion(ablated)
            metrics["zeroPcaAblation"] = {
                "description": "Test rows with V1..V28 replaced by 0 (what an amount/time-only app would send)",
                **ablation,
                "flaggedShare": (ablation["truePositive"] + ablation["falsePositive"]) / metrics["testCount"],
                "recallDrop": metrics["recall"] - ablation["recall"],
            }
            ablated.unpersist()

        model.write().overwrite().save(args.model_output)
        output.write_text(json.dumps(metrics, indent=2), encoding="utf-8")
        print(json.dumps({key: metrics[key] for key in ("precision", "recall", "f1", "areaUnderPR")}, indent=2))
        if "zeroPcaAblation" in metrics:
            print(json.dumps(metrics["zeroPcaAblation"], indent=2))
        predictions.unpersist()
        test.unpersist()
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
