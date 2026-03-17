FROM python:3.12-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl jq bash \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /governance
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN chmod +x schema-compat-check.sh

CMD ["python", "lint-contracts.py"]
