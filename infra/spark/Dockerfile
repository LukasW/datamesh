FROM eclipse-temurin:17-jre-noble

RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/opt/java/openjdk

# Ubuntu 24.04 enforces PEP 668 – use a venv to avoid conflicts
RUN python3 -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH

RUN pip install --no-cache-dir pyspark==3.5.1 delta-spark==3.1.0 \
    # Make spark-submit available on PATH so the CMD below is portable.
    && ln -s \
        "$(python3 -c 'import pyspark,os; print(os.path.join(os.path.dirname(pyspark.__file__),"bin","spark-submit"))')" \
        /usr/local/bin/spark-submit

ENV PYSPARK_PYTHON=/opt/venv/bin/python3

WORKDIR /app
COPY streaming.py .

# spark-submit resolves --packages *before* the Python script starts,
# so the Delta JARs are on the JVM classpath when SparkSession (and its
# SQL extension registry) initialises.  Running via plain "python3 script.py"
# adds jars too late and the DeltaSparkSessionExtension never loads.
CMD ["spark-submit", \
     "--packages", "io.delta:delta-spark_2.12:3.1.0,org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.1", \
     "--conf", "spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension", \
     "--conf", "spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog", \
     "/app/streaming.py"]
