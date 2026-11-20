FROM docker.io/library/python:3.12.16-slim-trixie@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b AS python

FROM python AS packages
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --require-hashes --only-binary=:all: --target /packages -r /tmp/requirements.txt

FROM packages AS model
ARG MODEL_REPOSITORY=nvidia/Nemotron-3.5-Embed-1B-BF16
ARG MODEL_REVISION=e24ca1e346bcc53572bd7bdc02b3e8ebf66c197c
RUN PYTHONPATH=/packages python -c "import sys; from huggingface_hub import snapshot_download; snapshot_download(sys.argv[1], revision=sys.argv[2], local_dir='/model')" "$MODEL_REPOSITORY" "$MODEL_REVISION" \
    && rm -rf /model/.cache

FROM python
COPY --from=packages /packages /packages
COPY --from=model /model /model
COPY embedding_api.py server.py /app/
ENV PYTHONPATH=/packages \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HUB_OFFLINE=1 \
    HF_HOME=/tmp/hf \
    TOKENIZERS_PARALLELISM=false \
    EMBEDDER_MODEL_PATH=/model \
    EMBEDDER_PORT=8080
USER 10001
WORKDIR /app
ENTRYPOINT ["python", "server.py"]
