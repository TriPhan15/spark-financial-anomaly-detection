# bookworm: the newer default base (trixie) no longer ships OpenJDK 17.
FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SPARK_LOCAL_IP=127.0.0.1 \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    FRAUD_LOAD_MODEL=true

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py behavior_api.py behavior_features.py ./
COPY random_forest_model ./random_forest_model
COPY behavior_model ./behavior_model

EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001", "--log-level", "info"]
