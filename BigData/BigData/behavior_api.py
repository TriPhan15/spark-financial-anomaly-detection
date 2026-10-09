"""Model B serving: POST /v2/predict-anomaly.

The app sends the receipt being saved plus the user's recent transactions. The API computes the
behavior features with behavior_features.py (the same definitions the Spark job used for training),
then scores them with the Spark PipelineModel trained by train_behavior.py. The decision threshold
comes from the model metadata, chosen on the validation split.
"""

import json
import logging
import math
import os
import time
from datetime import datetime
from pathlib import Path

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator
from pyspark.ml import PipelineModel
from pyspark.sql import SparkSession
from pyspark.sql.types import DoubleType, StructField, StructType

from behavior_features import FEATURE_COLUMNS, MIN_HISTORY, Txn, compute_features, explain

BASE_DIR = Path(__file__).resolve().parent
BEHAVIOR_MODEL_PATH = Path(os.getenv("BEHAVIOR_MODEL_PATH", str(BASE_DIR / "behavior_model")))
MAX_HISTORY_ITEMS = 200

logger = logging.getLogger("behavior_api")


class TransactionIn(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    amount: float = Field(gt=0)
    occurred_at: datetime = Field(alias="occurredAt", description="Local date-time without time zone")
    category: str = Field(min_length=1, max_length=100)

    @field_validator("amount")
    @classmethod
    def finite_amount(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("amount must be finite")
        return value

    def to_txn(self) -> Txn:
        return Txn(amount=self.amount, occurred_at=self.occurred_at.replace(tzinfo=None), category=self.category)


class AnomalyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    current: TransactionIn
    history: list[TransactionIn] = Field(default_factory=list, max_length=MAX_HISTORY_ITEMS)


class AnomalyResponse(BaseModel):
    scored: bool
    reason: str | None = None
    isAnomaly: bool | None = None
    riskScore: float | None = Field(default=None, ge=0, le=1)
    threshold: float | None = None
    modelVersion: str
    explanation: str | None = None
    features: dict[str, float] | None = None


class BehaviorEngine:
    def __init__(self) -> None:
        self.spark: SparkSession | None = None
        self.model: PipelineModel | None = None
        self.metadata: dict = {}
        self.schema = StructType([StructField(column, DoubleType(), nullable=False) for column in FEATURE_COLUMNS])

    @property
    def loaded(self) -> bool:
        return self.model is not None

    def load(self, spark: SparkSession) -> None:
        metadata_path = BEHAVIOR_MODEL_PATH / "behavior_metadata.json"
        if not metadata_path.is_file():
            raise RuntimeError(f"Behavior model metadata does not exist: {metadata_path}")
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        if metadata.get("featureColumns") != FEATURE_COLUMNS:
            raise RuntimeError("Behavior model feature order does not match behavior_features.FEATURE_COLUMNS")
        if metadata.get("minHistory") != MIN_HISTORY:
            raise RuntimeError("Behavior model cold-start rule does not match behavior_features.MIN_HISTORY")
        self.spark = spark
        self.model = PipelineModel.load(str(BEHAVIOR_MODEL_PATH))
        self.metadata = metadata
        # Warm-up: the first Spark job compiles code and starts threads; do it before any user request.
        started = time.perf_counter()
        self._score({column: 1.0 for column in FEATURE_COLUMNS})
        logger.info("Behavior model %s warmed up in %.2fs", metadata["modelVersion"], time.perf_counter() - started)

    def _score(self, features: dict[str, float]) -> float:
        frame = self.spark.createDataFrame([tuple(features[column] for column in FEATURE_COLUMNS)], schema=self.schema)
        row = self.model.transform(frame).select("probability").first()
        if row is None:
            raise RuntimeError("Behavior model returned no prediction")
        return float(row["probability"][1])

    def predict(self, request: AnomalyRequest) -> AnomalyResponse:
        if not self.loaded or self.spark is None:
            raise RuntimeError("Behavior model is not loaded")
        version = self.metadata["modelVersion"]
        features = compute_features(request.current.to_txn(), [item.to_txn() for item in request.history])
        if features is None:
            return AnomalyResponse(scored=False, reason="insufficient_history", modelVersion=version)

        threshold = float(self.metadata["threshold"])
        risk_score = self._score(features)
        is_anomaly = risk_score >= threshold
        return AnomalyResponse(
            scored=True,
            isAnomaly=is_anomaly,
            riskScore=round(risk_score, 6),
            threshold=threshold,
            modelVersion=version,
            explanation=explain(features) if is_anomaly else None,
            features=features,
        )

    def stop(self) -> None:
        self.model = None
        self.spark = None


MAX_SCAN_ITEMS = 1000


class ScanTransactionIn(TransactionIn):
    id: str | None = Field(default=None, max_length=64)


class ScanRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    transactions: list[ScanTransactionIn] = Field(default_factory=list, max_length=MAX_SCAN_ITEMS)


class FlaggedTransaction(BaseModel):
    id: str | None = None
    amount: float
    occurredAt: str
    category: str
    riskScore: float
    explanation: str | None = None


class ScanResponse(BaseModel):
    modelVersion: str
    threshold: float
    scannedCount: int
    scoredCount: int
    flaggedCount: int
    flaggedByCategory: dict[str, int]
    flagged: list[FlaggedTransaction]


def _scan(engine: BehaviorEngine, request: ScanRequest) -> ScanResponse:
    if not engine.loaded or engine.spark is None:
        raise RuntimeError("Behavior model is not loaded")
    items = sorted(request.transactions, key=lambda item: item.occurred_at.replace(tzinfo=None))
    txns = [item.to_txn() for item in items]
    rows, owners, all_features = [], [], []
    for index, txn in enumerate(txns):
        features = compute_features(txn, txns[max(0, index - MAX_HISTORY_ITEMS):index])
        if features is not None:
            rows.append(tuple(features[column] for column in FEATURE_COLUMNS))
            owners.append(items[index])
            all_features.append(features)

    threshold = float(engine.metadata["threshold"])
    flagged: list[FlaggedTransaction] = []
    by_category: dict[str, int] = {}
    if rows:
        frame = engine.spark.createDataFrame(rows, schema=engine.schema)
        scores = [float(r["probability"][1]) for r in engine.model.transform(frame).select("probability").collect()]
        # transform keeps row order for a local, non-shuffling pipeline; the parity check in e2e_check guards this.
        for item, features, score in zip(owners, all_features, scores):
            if score >= threshold:
                by_category[item.category] = by_category.get(item.category, 0) + 1
                flagged.append(FlaggedTransaction(
                    id=item.id, amount=item.amount, occurredAt=item.occurred_at.isoformat(),
                    category=item.category, riskScore=round(score, 6), explanation=explain(features),
                ))
    flagged.sort(key=lambda entry: entry.riskScore, reverse=True)
    return ScanResponse(
        modelVersion=engine.metadata["modelVersion"], threshold=threshold, scannedCount=len(items),
        scoredCount=len(rows), flaggedCount=len(flagged), flaggedByCategory=by_category, flagged=flagged,
    )


behavior_engine = BehaviorEngine()
router = APIRouter(prefix="/v2", tags=["Model B - behavior anomaly"])


@router.post("/predict-anomaly", response_model=AnomalyResponse, response_model_exclude_none=False)
def predict_anomaly(request: AnomalyRequest) -> AnomalyResponse:
    started = time.perf_counter()
    try:
        response = behavior_engine.predict(request)
    except RuntimeError as error:
        logger.warning("Behavior prediction unavailable: %s", type(error).__name__)
        raise HTTPException(status_code=503, detail="Behavior model is temporarily unavailable") from error
    # Log only timing and outcome, never amounts or categories.
    logger.info("predict-anomaly scored=%s in %.0f ms", response.scored, (time.perf_counter() - started) * 1000)
    return response


@router.post("/scan-history", response_model=ScanResponse)
def scan_history(request: ScanRequest) -> ScanResponse:
    started = time.perf_counter()
    try:
        response = _scan(behavior_engine, request)
    except RuntimeError as error:
        logger.warning("Behavior scan unavailable: %s", type(error).__name__)
        raise HTTPException(status_code=503, detail="Behavior model is temporarily unavailable") from error
    logger.info(
        "scan-history scanned=%d scored=%d flagged=%d in %.0f ms",
        response.scannedCount, response.scoredCount, response.flaggedCount, (time.perf_counter() - started) * 1000,
    )
    return response
