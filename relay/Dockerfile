# syntax=docker/dockerfile:1
FROM python:3.12.12-slim
WORKDIR /app
COPY pyproject.toml requirements.lock ./
COPY python ./python
COPY relay ./relay
RUN --mount=type=secret,id=proxy_ca,required=false \
    if [ -f /run/secrets/proxy_ca ]; then PIP_CERT=/run/secrets/proxy_ca pip install --no-cache-dir -c requirements.lock .; else pip install --no-cache-dir -c requirements.lock .; fi \
    && useradd --uid 10001 --create-home bridge && mkdir /data && chown bridge /data
RUN chmod -R a+rX /app
USER bridge
ENV AB_DB=/data/relay.sqlite3 AB_TRUST_PROXY=1
CMD ["uvicorn", "relay.app:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log", "--ws-max-size", "25165824", "--no-proxy-headers"]
