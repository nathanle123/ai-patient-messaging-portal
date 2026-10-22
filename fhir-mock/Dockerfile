# syntax=docker/dockerfile:1
# Mock FHIR R4 server (Patient + Communication, in memory). fake_phi.csv is mounted at runtime.
FROM python:3.11.13-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    FAKE_PHI_PATH=/app/seed/fake_phi.csv
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY requirements.txt requirements-test.txt ./
RUN pip install -r requirements.txt -r requirements-test.txt
COPY pytest.ini ./
COPY fhir_mock ./fhir_mock
COPY tests ./tests
USER app
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"
CMD ["uvicorn", "fhir_mock.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
