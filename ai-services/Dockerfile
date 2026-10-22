# syntax=docker/dockerfile:1
# One Dockerfile, one target per service. Stages share layers (spaCy, torch, MiniLM) so the five
# images cost far less disk than five independent builds. Every model is downloaded here, at build
# time, at a pinned revision; containers run with HF_HUB_OFFLINE=1.
#
#   docker build --target phi-detection -t portal/phi-detection .

FROM python:3.11.13-slim-bookworm AS base
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    OMP_NUM_THREADS=2 \
    TORCH_THREADS=2 \
    TOKENIZERS_PARALLELISM=false \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    HF_HUB_DISABLE_TELEMETRY=1
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY requirements/constraints.txt requirements/base.txt requirements/test.txt requirements/kafka.txt requirements/
RUN pip install -c requirements/constraints.txt -r requirements/base.txt -r requirements/test.txt -r requirements/kafka.txt
COPY pytest.ini ./
COPY portal_common ./portal_common
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4)"

# ---------- spaCy services ----------
FROM base AS spacy-base
COPY requirements/phi.txt requirements/urgency.txt requirements/
RUN pip install -c requirements/constraints.txt spacy==3.8.16

FROM spacy-base AS phi-detection
RUN pip install -c requirements/constraints.txt -r requirements/phi.txt \
 && pip install "en_core_web_lg @ https://github.com/explosion/spacy-models/releases/download/en_core_web_lg-3.8.0/en_core_web_lg-3.8.0-py3-none-any.whl#sha256=293e9547a655b25499198ab15a525b05b9407a75f10255e405e8c3854329ab63"
COPY phi_detection ./phi_detection
USER app
CMD ["uvicorn", "phi_detection.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]

FROM spacy-base AS urgency
RUN pip install -c requirements/constraints.txt -r requirements/urgency.txt \
 && pip install "en_core_web_sm @ https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl#sha256=1932429db727d4bff3deed6b34cfc05df17794f4a52eeb26cf8928f7c1a0fb85"
COPY urgency ./urgency
USER app
CMD ["uvicorn", "urgency.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]

# ---------- transformer services ----------
FROM base AS ml-base
COPY requirements/torch.txt requirements/ml.txt requirements/
RUN pip install -c requirements/constraints.txt --index-url https://download.pytorch.org/whl/cpu -r requirements/torch.txt \
 && pip install -c requirements/constraints.txt -r requirements/ml.txt
COPY scripts ./scripts

FROM ml-base AS routing
RUN HF_HUB_OFFLINE=0 python scripts/download_model.py \
      MoritzLaurer/deberta-v3-base-zeroshot-v2.0 8e7e5af5983a0ddb1a5b45a38b129ab69e2258e8 \
      /models/deberta-v3-base-zeroshot-v2.0 \
      config.json model.safetensors tokenizer.json tokenizer_config.json special_tokens_map.json \
      added_tokens.json spm.model
COPY routing ./routing
# min(TORCH_THREADS_CAP=2, vCPUs / ROUTING_REPLICAS) torch threads per replica (routing/engine.py:torch_threads).
ENV TORCH_THREADS=auto
USER app
CMD ["uvicorn", "routing.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]

FROM ml-base AS minilm
RUN HF_HUB_OFFLINE=0 python scripts/download_model.py \
      sentence-transformers/all-MiniLM-L6-v2 1110a243fdf4706b3f48f1d95db1a4f5529b4d41 \
      /models/all-MiniLM-L6-v2 \
      modules.json config_sentence_transformers.json sentence_bert_config.json "1_Pooling/*" \
      config.json model.safetensors tokenizer.json tokenizer_config.json special_tokens_map.json vocab.txt

FROM minilm AS summarization
COPY summarization ./summarization
USER app
CMD ["uvicorn", "summarization.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]

FROM minilm AS similarity-search
COPY requirements/search.txt requirements/
RUN pip install -c requirements/constraints.txt -r requirements/search.txt
COPY similarity_search ./similarity_search
USER app
CMD ["uvicorn", "similarity_search.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
