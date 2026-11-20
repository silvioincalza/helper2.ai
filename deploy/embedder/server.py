import json
import os
import signal
import sys
import threading

import torch
from sentence_transformers import SentenceTransformer

from embedding_api import EmbeddingService, server_for


def setting(name):
    value = os.environ.get(name, "").strip()
    if not value:
        sys.exit(f"{name} is not set")
    return value


def load_model(path, max_tokens, threads):
    torch.set_num_threads(threads)
    model = SentenceTransformer(
        path,
        device="cpu",
        local_files_only=True,
        model_kwargs={"dtype": torch.float32, "attn_implementation": "sdpa"},
    )
    model.max_seq_length = max_tokens
    return model


def encoder(model):
    def encode(texts, input_type):
        embed = model.encode_query if input_type == "query" else model.encode_document
        with torch.inference_mode():
            return embed(texts, batch_size=1, convert_to_numpy=True, normalize_embeddings=True)

    return encode


def warm_up_call(served_model):
    return json.dumps({"model": served_model, "input": ["warm up"], "input_type": "query"})


def main():
    model = load_model(
        setting("EMBEDDER_MODEL_PATH"),
        int(setting("EMBEDDER_MAX_TOKENS")),
        int(setting("EMBEDDER_THREADS")),
    )
    service = EmbeddingService(
        setting("EMBEDDER_MODEL_NAME"), model.get_sentence_embedding_dimension(), encoder(model)
    )
    service.embed(warm_up_call(service.served_model))
    server = server_for(service, "0.0.0.0", int(setting("EMBEDDER_PORT")))
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown).start())
    print(
        f"embedder ready: model={service.served_model} dimensions={service.native_dimensions}",
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
