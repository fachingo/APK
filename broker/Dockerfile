# syntax=docker/dockerfile:1
FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY broker/requirements.txt /app/requirements.txt
RUN --mount=type=secret,id=proxy_ca \
    if [ -f /run/secrets/proxy_ca ]; then export PIP_CERT=/run/secrets/proxy_ca; fi; \
    pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home broker && mkdir /data && chown broker:broker /data
COPY --chown=broker:broker broker /app/broker
USER 10001
EXPOSE 8080
CMD ["uvicorn","broker.app:create_app","--factory","--host","0.0.0.0","--port","8080","--workers","1","--no-access-log"]
