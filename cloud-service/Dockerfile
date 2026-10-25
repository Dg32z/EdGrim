# Python 3.12 rather than the 3.14 on the development machine: LightGBM ships wheels for 3.12, and a
# production image should not depend on a source build. The code itself runs on both.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY run.py ./

# Everything worth keeping lives on one volume: the generated config.yaml and the SQLite database
# beside it. Nothing is configured by environment variable.
VOLUME ["/data"]

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/metrics', timeout=2)"

CMD ["python", "run.py", "--config", "/data/config.yaml"]
