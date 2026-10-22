# Spark runtime for the coursework cluster: the official image plus the Python packages that
# pyspark.ml and the executed notebooks need (numpy is required by pyspark.ml).
FROM apache/spark:4.0.1-python3

USER root
RUN pip install --no-cache-dir numpy==1.26.4 pandas==2.2.3 matplotlib==3.9.2 \
        nbconvert==7.16.4 ipykernel==6.29.5 nbformat==5.10.4
USER spark
