from contextlib import asynccontextmanager
import logging
import math
import os
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator
from pyspark.ml.classification import RandomForestClassificationModel
from pyspark.ml.feature import VectorAssembler
from pyspark.sql import SparkSession
from pyspark.sql.types import DoubleType, StructField, StructType

from behavior_api import behavior_engine, router as behavior_router

BASE_DIR = Path(__file__).resolve().parent
MODEL_PATH = Path(os.getenv("FRAUD_MODEL_PATH", str(BASE_DIR / "random_forest_model")))
APP_NAME = os.getenv("FRAUD_SPARK_APP_NAME", "LedgerMindFraudDetection")
FEATURE_COLUMNS = ["Time", *[f"V{i}" for i in range(1, 29)], "Amount"]

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("fraud_api")


class FraudRequest(BaseModel):
    """Model A (Kaggle credit-card benchmark): the exact 30-feature contract of the trained Spark model.

    Used to demonstrate the benchmark with real test rows. The mobile app does not call it, because
    receipts have no V1..V28; it calls /v2/predict-anomaly (Model B) instead.
    """

    model_config = ConfigDict(extra="forbid")

    time: float = Field(ge=0)
    amount: float = Field(ge=0)
    v_features: list[float] = Field(
        alias="vFeatures",
        min_length=28,
        max_length=28,
        description="The V1..V28 PCA features from the evaluated transaction.",
    )

    @field_validator("time", "amount")
    @classmethod
    def finite_scalar(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("feature must be finite")
        return value

    @field_validator("v_features")
    @classmethod
    def finite_vector(cls, value: list[float]) -> list[float]:
        if not all(math.isfinite(item) for item in value):
            raise ValueError("V features must be finite")
        return value


class FraudResponse(BaseModel):
    is_fraud: bool
    risk_score: float = Field(ge=0, le=1)
    model_version: str
    message: str


class FraudEngine:
    def __init__(self) -> None:
        self.spark: SparkSession | None = None
        self.model: RandomForestClassificationModel | None = None
        self.assembler: VectorAssembler | None = None

    @property
    def loaded(self) -> bool:
        return self.model is not None and self.assembler is not None

    def load(self, spark: SparkSession) -> None:
        if not MODEL_PATH.is_dir():
            raise RuntimeError(f"Fraud model directory does not exist: {MODEL_PATH}")

        self.spark = spark
        self.model = RandomForestClassificationModel.load(str(MODEL_PATH))
        expected_features = self.model.numFeatures
        if expected_features != len(FEATURE_COLUMNS):
            raise RuntimeError(
                f"Model expects {expected_features} features, but the API contract has "
                f"{len(FEATURE_COLUMNS)}"
            )
        self.assembler = VectorAssembler(
            inputCols=FEATURE_COLUMNS,
            outputCol="features",
        )

    def predict(self, request: FraudRequest) -> FraudResponse:
        if not self.loaded or self.spark is None or self.model is None or self.assembler is None:
            raise RuntimeError("Fraud model is not loaded")

        row = [(request.time, *request.v_features, request.amount)]
        schema = StructType(
            [StructField(column, DoubleType(), nullable=False) for column in FEATURE_COLUMNS]
        )
        frame = self.spark.createDataFrame(row, schema=schema)
        prediction = self.model.transform(self.assembler.transform(frame)).select(
            "prediction", "probability"
        ).first()
        if prediction is None:
            raise RuntimeError("Fraud model returned no prediction")

        risk_score = float(prediction["probability"][1])
        is_fraud = float(prediction["prediction"]) == 1.0
        return FraudResponse(
            is_fraud=is_fraud,
            risk_score=round(risk_score, 4),
            model_version=MODEL_PATH.name,
            message=(
                "Giao dịch thẻ có dấu hiệu gian lận theo mô hình benchmark."
                if is_fraud
                else "Giao dịch thẻ không có dấu hiệu gian lận theo mô hình benchmark."
            ),
        )

    def stop(self) -> None:
        self.spark = None
        self.model = None
        self.assembler = None


engine = FraudEngine()


@asynccontextmanager
async def lifespan(_: FastAPI):
    spark = None
    if os.getenv("FRAUD_LOAD_MODEL", "true").lower() not in {"0", "false", "no"}:
        spark = SparkSession.builder.appName(APP_NAME).master("local[*]").getOrCreate()
        spark.sparkContext.setLogLevel("WARN")
        # Each model loads on its own: a missing Model A must not take Model B (used by the app) down.
        for name, load in (("Model A", lambda: engine.load(spark)), ("Model B", lambda: behavior_engine.load(spark))):
            try:
                load()
            except Exception:
                logger.exception("%s could not be loaded", name)
    yield
    engine.stop()
    behavior_engine.stop()
    if spark is not None:
        spark.stop()


app = FastAPI(title="Fraud Detection API", version="2.0.0", lifespan=lifespan)
app.include_router(behavior_router)


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "modelLoaded": engine.loaded,
        "featureCount": len(FEATURE_COLUMNS),
        "behaviorModelLoaded": behavior_engine.loaded,
        "behaviorModelVersion": behavior_engine.metadata.get("modelVersion"),
    }


@app.post("/predict-fraud", response_model=FraudResponse)
def predict_fraud(request: FraudRequest) -> FraudResponse:
    try:
        return engine.predict(request)
    except RuntimeError as error:
        raise HTTPException(status_code=503, detail="Fraud model is temporarily unavailable") from error