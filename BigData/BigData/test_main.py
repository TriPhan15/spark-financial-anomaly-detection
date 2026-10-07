import importlib

from fastapi.testclient import TestClient


def load_app(monkeypatch):
    monkeypatch.setenv("FRAUD_LOAD_MODEL", "false")
    module = importlib.import_module("main")
    return module


def test_health_reports_unloaded_model(monkeypatch):
    module = load_app(monkeypatch)
    client = TestClient(module.app)
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["modelLoaded"] is False


def test_prediction_requires_exact_feature_vector(monkeypatch):
    module = load_app(monkeypatch)
    client = TestClient(module.app)
    response = client.post("/predict-fraud", json={"time": 1, "amount": 10, "vFeatures": [0]})
    assert response.status_code == 422


def test_prediction_does_not_generate_missing_features(monkeypatch):
    module = load_app(monkeypatch)

    class StubEngine:
        loaded = True

        def predict(self, request):
            assert len(request.v_features) == 28
            return module.FraudResponse(
                is_fraud=False,
                risk_score=0.1,
                model_version="test",
                message="ok",
            )

    module.engine = StubEngine()
    client = TestClient(module.app)
    response = client.post(
        "/predict-fraud",
        json={"time": 1, "amount": 10, "vFeatures": [0] * 28},
    )
    assert response.status_code == 200
    assert response.json()["model_version"] == "test"
