"""Behavior features for Model B, in pure Python (serving side).

This module is the contract between the Spark training job (behavior_features_spark.py) and the API.
Both sides must produce the same numbers for the same transactions; scripts/e2e_check.py replays
Spark-computed samples through the API to prove it.

Definitions (spec section 5). For the current transaction x of a user at time t, H is the at most
HISTORY_WINDOW most recent transactions of that user strictly before t:

    hour               hour of t (0-23), local time
    day_of_week        0 = Monday ... 6 = Sunday
    category_idx       position of the Sparkov category in SPARKOV_CATEGORIES
    amt_ratio_user     amount / mean(amount of H)
    amt_ratio_user_cat amount / mean(amount of H in the same category), else amt_ratio_user
    txn_count_24h      number of H in [t - 86400 s, t)
    secs_since_prev    t - time of the latest transaction in H

Ratios instead of absolute amounts make the model independent of the currency (USD in Sparkov,
VND in the app). Fewer than MIN_HISTORY transactions in H means cold start: no score.
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone

SPARKOV_CATEGORIES = [
    "entertainment",
    "food_dining",
    "gas_transport",
    "grocery_net",
    "grocery_pos",
    "health_fitness",
    "home",
    "kids_pets",
    "misc_net",
    "misc_pos",
    "personal_care",
    "shopping_net",
    "shopping_pos",
    "travel",
]
FALLBACK_CATEGORY = "misc_pos"

FEATURE_COLUMNS = [
    "hour",
    "day_of_week",
    "category_idx",
    "amt_ratio_user",
    "amt_ratio_user_cat",
    "txn_count_24h",
    "secs_since_prev",
]

HISTORY_WINDOW = 100
MIN_HISTORY = 5
SECONDS_PER_DAY = 86_400

# App categories are created by admins in Vietnamese, so they are matched by keywords after removing
# diacritics and matched as whole words. The first matching rule wins; anything else falls back to misc_pos.
_CATEGORY_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("food_dining", ("an uong", "an", "uong", "ca phe", "cafe", "coffee", "tra sua", "nha hang", "quan an", "do an", "food")),
    ("gas_transport", ("xang", "di chuyen", "giao thong", "xe", "grab", "taxi", "gui xe", "transport")),
    ("kids_pets", ("con cai", "tre em", "em be", "thu cung", "cho meo", "pet", "kids")),
    ("grocery_net", ("di cho online", "bach hoa online")),
    ("grocery_pos", ("sieu thi", "di cho", "cho", "tap hoa", "bach hoa", "thuc pham", "grocery")),
    ("shopping_net", ("mua sam online", "shopee", "lazada", "tiki", "online")),
    ("shopping_pos", ("mua sam", "quan ao", "giay dep", "thoi trang", "shopping")),
    ("travel", ("du lich", "khach san", "ve may bay", "travel", "hotel")),
    ("entertainment", ("giai tri", "phim", "rap phim", "game", "karaoke", "entertainment")),
    ("health_fitness", ("suc khoe", "y te", "thuoc", "benh vien", "gym", "the thao", "health")),
    ("personal_care", ("lam dep", "cat toc", "my pham", "spa", "cham soc ca nhan")),
    ("home", ("nha", "nha cua", "dien", "nuoc", "dien nuoc", "internet", "wifi", "thue nha", "hoa don", "home")),
    ("misc_net", ("dich vu online", "dang ky", "subscription")),
]


@dataclass(frozen=True)
class Txn:
    amount: float
    occurred_at: datetime
    category: str


def to_epoch_seconds(value: datetime) -> float:
    """Naive local time to seconds, read as if it were UTC (the Spark job sets session time zone UTC)."""
    if value.tzinfo is not None:
        value = value.replace(tzinfo=None)
    return value.replace(tzinfo=timezone.utc).timestamp()


def _normalize(text: str) -> str:
    decomposed = unicodedata.normalize("NFD", text.strip().lower().replace("đ", "d"))
    return "".join(char for char in decomposed if unicodedata.category(char) != "Mn")


def map_category(name: str) -> str:
    """Maps an app category name (or a Sparkov name) to one of SPARKOV_CATEGORIES."""
    normalized = _normalize(name)
    if normalized in SPARKOV_CATEGORIES:
        return normalized
    # Whole-word matching on the padded text, so "an" (eat) does not match inside "ban" (sell).
    padded = " " + "".join(char if char.isalnum() else " " for char in normalized) + " "
    padded = " ".join(padded.split()).join("  ")
    for category, keywords in _CATEGORY_KEYWORDS:
        if any(f" {keyword} " in padded for keyword in keywords):
            return category
    return FALLBACK_CATEGORY


def _window(current_ts: float, history: list[tuple[float, float, str]]) -> list[tuple[float, float, str]]:
    """H: history strictly before t, positive amounts only, the HISTORY_WINDOW most recent."""
    earlier = sorted(
        (item for item in history if item[0] < current_ts and item[1] > 0),
        key=lambda item: item[0],
    )
    return earlier[-HISTORY_WINDOW:]


def compute_features(current: Txn, history: list[Txn]) -> dict[str, float] | None:
    """The 7 model features, or None when the user has fewer than MIN_HISTORY earlier transactions."""
    t = to_epoch_seconds(current.occurred_at)
    category = map_category(current.category)
    window = _window(
        t,
        [(to_epoch_seconds(item.occurred_at), float(item.amount), map_category(item.category)) for item in history],
    )
    if len(window) < MIN_HISTORY:
        return None

    amounts = [amount for _, amount, _ in window]
    same_category = [amount for _, amount, item_category in window if item_category == category]
    ratio_user = current.amount / (sum(amounts) / len(amounts))
    ratio_category = current.amount / (sum(same_category) / len(same_category)) if same_category else ratio_user

    local = current.occurred_at.replace(tzinfo=None)
    return {
        "hour": float(local.hour),
        "day_of_week": float(local.weekday()),
        "category_idx": float(SPARKOV_CATEGORIES.index(category)),
        "amt_ratio_user": ratio_user,
        "amt_ratio_user_cat": ratio_category,
        "txn_count_24h": float(sum(1 for ts, _, _ in window if ts >= t - SECONDS_PER_DAY)),
        "secs_since_prev": t - max(ts for ts, _, _ in window),
    }


def _format_ratio(value: float) -> str:
    return f"{value:.1f}".replace(".", ",")


def explain(features: dict[str, float]) -> str:
    """A short Vietnamese reason shown in the app, derived from the feature values (rule based)."""
    ratio_category = features["amt_ratio_user_cat"]
    ratio_user = features["amt_ratio_user"]
    if max(ratio_category, ratio_user) >= 3:
        # Equal ratios mean the category had no history (fallback), so the user-wide wording is the true one.
        if ratio_category > ratio_user:
            return f"Số tiền gấp {_format_ratio(ratio_category)} lần mức chi trung bình của bạn cho danh mục này."
        return f"Số tiền gấp {_format_ratio(ratio_user)} lần mức chi trung bình của bạn."
    if features["txn_count_24h"] >= 5:
        return f"Bạn đã có {int(features['txn_count_24h'])} giao dịch trong 24 giờ qua, nhiều hơn thường lệ."
    if features["hour"] < 5:
        return f"Giao dịch vào khung giờ khuya ({int(features['hour'])} giờ), khác thói quen thường ngày."
    if features["secs_since_prev"] < 600:
        return "Giao dịch xảy ra ngay sau giao dịch trước đó, khác nhịp chi tiêu thường ngày."
    return "Mẫu chi tiêu này khác với thói quen gần đây của bạn."
