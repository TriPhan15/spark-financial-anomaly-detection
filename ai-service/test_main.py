"""Unit test cho ai_service.py bang mock, khong tai model Donut vat ly."""

from io import BytesIO
from unittest.mock import patch

from PIL import Image

# --- Global Import Trap ---------------------------------------------------
# CI khong co file weights that (Version3/donut_result), va ai_service.py
# tai model ngay o top-level khi import module (khong nam trong ham/lifespan).
# Cac patch() nay PHAI chay va .start() truoc dong "from ai_service import app"
# ben duoi de chan viec load model vat ly + khoi tao class that.
patch("os.path.exists", return_value=True).start()
patch("transformers.DonutProcessor.from_pretrained").start()
patch("transformers.AutoTokenizer.from_pretrained").start()
patch("transformers.VisionEncoderDecoderModel.from_pretrained").start()

from fastapi.testclient import TestClient  # noqa: E402

import ai_service  # noqa: E402
from ai_service import app  # noqa: E402
from wcm_spade import Pattern, WCMSpadeResult  # noqa: E402

client = TestClient(app)


def _make_test_image_bytes() -> bytes:
    """Tao anh JPEG 1x1 pixel hop le trong bo nho de gui qua /donut/scan."""
    buffer = BytesIO()
    Image.new("RGB", (1, 1), color=(255, 0, 0)).save(buffer, format="JPEG")
    return buffer.getvalue()


def test_health_check():
    """GET /health tra ve HTTP 200 va trang thai ok."""
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_extract_receipt_info_success(mocker):
    """POST /donut/scan voi anh hop le tra ve JSON da boc tach kem
    total_cost_number tinh tu chuoi tien te cua Donut."""
    # Gan truc tiep: processor.tokenizer la MagicMock (bi mock o phan Global
    # Import Trap phia tren), can ep cac thuoc tinh token ve kieu du lieu
    # that (int/str) de tranh loi PyTorch/String khi endpoint gop chuoi.
    ai_service.processor.tokenizer.convert_tokens_to_ids.return_value = 1
    ai_service.processor.tokenizer.pad_token_id = 0
    ai_service.processor.tokenizer.eos_token_id = 2
    ai_service.processor.tokenizer.eos_token = "<eos>"
    ai_service.processor.tokenizer.pad_token = "<pad>"

    # Mock: bo qua suy luan PyTorch that, ep batch_decode tra ve chuoi token
    # tho nhu the model vua sinh xong.
    mocker.patch.object(
        ai_service.processor,
        "batch_decode",
        return_value=["<s_seller>dummy text</s_seller>"],
    )
    # Mock: ep token2json tra thang JSON long nhau chuan, bo qua logic
    # decode that cua Donut processor.
    mocker.patch.object(
        ai_service.processor,
        "token2json",
        return_value={
            "seller": {"total_cost": "100.000đ"},
            "address": "123 Test",
            "timestamp": "2026-09-22",
        },
    )

    response = client.post(
        "/donut/scan",
        files={"image": ("receipt.jpg", _make_test_image_bytes(), "image/jpeg")},
    )

    assert response.status_code == 200
    assert response.json()["seller"]["total_cost_number"] == 100000


def test_extract_receipt_info_missing_file():
    """POST /donut/scan thieu file anh bat buoc phai bi FastAPI chan o
    buoc validation (422), chua cham toi logic model."""
    response = client.post("/donut/scan")

    assert response.status_code == 422


def test_spade_analyze(mocker):
    """POST /spade/analyze tra ve patterns lay tu WCMSpade.mine (da mock),
    khong phu thuoc thuat toan khai pha chuoi that."""
    fake_pattern = Pattern(
        itemsets=[["an uong"], ["di lai"]], idlist={}, raw_sup=2, wsup=1.5
    )
    fake_result = WCMSpadeResult(
        patterns=[fake_pattern],
        n_sequences=2,
        min_sup_abs=1,
        effective_min_sup=1,
        mean_w=0.75,
        item_weights={"an uong": 1.0, "di lai": 0.5},
    )
    # Mock: chan class wcm_spade.WCMSpade.mine, ep tra ve WCMSpadeResult
    # gia lap de chi kiem tra tang API/serialization, khong chay thuat
    # toan WCM-SPADE that.
    mocker.patch("wcm_spade.WCMSpade.mine", return_value=fake_result)

    payload = {
        "transactions": [
            {
                "category": "an uong",
                "amount": 50000,
                "date": "2026-09-01",
                "isExpense": True,
            },
            {
                "category": "di lai",
                "amount": 20000,
                "date": "2026-09-02",
                "isExpense": True,
            },
            {
                "category": "an uong",
                "amount": 30000,
                "date": "2026-09-15",
                "isExpense": True,
            },
        ],
        "top_n": 5,
    }
    response = client.post("/spade/analyze", json=payload)

    assert response.status_code == 200
    assert response.json()["patterns"] == [
        {
            "pattern": "an uong → di lai",
            "count": 2,
            "weight": 1.5,
            "wsupport": "100.0%",
        }
    ]


def test_recommend_budget(mocker):
    """POST /spade/recommend tra ve loi khuyen lay tu budget_advisor.advise
    (da mock), khong phu thuoc thuat toan tinh ngan sach that."""
    fake_advice = {
        "trends": [],
        "tagStats": [],
        "goal": {
            "neededMonthly": 1000000,
            "monthlyIncome": 5000000,
            "monthlyExpense": 3000000,
            "surplus": 2000000,
            "gap": -1000000,
        },
        "recommendations": [],
        "summary": "Đề xuất ngân sách giả lập cho kiểm thử.",
    }
    # Mock: chan ham budget_advisor.advise, ep tra ve dict loi khuyen gia
    # lap de chi kiem tra endpoint truyen/tra du lieu dung.
    mocker.patch("budget_advisor.advise", return_value=fake_advice)

    payload = {
        "transactions": [
            {
                "category": "an uong",
                "amount": 50000,
                "date": "2026-09-01",
                "isExpense": True,
            },
        ],
        "target": 10000000,
        "current": 2000000,
        "months_left": 6,
    }
    response = client.post("/spade/recommend", json=payload)

    assert response.status_code == 200
    assert response.json()["summary"] == "Đề xuất ngân sách giả lập cho kiểm thử."
