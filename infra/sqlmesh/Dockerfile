FROM python:3.12-slim

RUN pip install --no-cache-dir "sqlmesh[trino]"

WORKDIR /sqlmesh
COPY config.yaml .
COPY models/ models/

ENTRYPOINT ["sqlmesh"]
CMD ["plan", "--auto-apply"]
