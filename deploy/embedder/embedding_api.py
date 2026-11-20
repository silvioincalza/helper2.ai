import json
import math
import threading
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY_BYTES = 4 * 1024 * 1024
MAX_INPUTS = 32
MIN_DIMENSIONS = 128
INPUT_TYPES = ("query", "passage")


class BadRequest(Exception):
    pass


@dataclass(frozen=True)
class EmbeddingRequest:
    texts: list
    input_type: str
    dimensions: int


def parse_request(body, served_model, native_dimensions):
    try:
        payload = json.loads(body)
    except ValueError as e:
        raise BadRequest("the body is not JSON") from e
    if not isinstance(payload, dict):
        raise BadRequest("the body must be a JSON object")
    if payload.get("model") != served_model:
        raise BadRequest(f"model must be {served_model}")
    return EmbeddingRequest(
        texts=_texts(payload.get("input")),
        input_type=_input_type(payload.get("input_type")),
        dimensions=_dimensions(payload.get("dimensions"), native_dimensions),
    )


def _texts(value):
    texts = [value] if isinstance(value, str) else value
    if not isinstance(texts, list) or not texts:
        raise BadRequest("input must be a text or a list of texts")
    if len(texts) > MAX_INPUTS:
        raise BadRequest(f"input holds more than {MAX_INPUTS} texts")
    if not all(isinstance(text, str) and text for text in texts):
        raise BadRequest("every input must be a text that is not empty")
    return texts


def _input_type(value):
    if value not in INPUT_TYPES:
        raise BadRequest("input_type must be query or passage")
    return value


def _dimensions(value, native_dimensions):
    if value is None:
        return native_dimensions
    if isinstance(value, bool) or not isinstance(value, int):
        raise BadRequest("dimensions must be a whole number")
    if not MIN_DIMENSIONS <= value <= native_dimensions:
        raise BadRequest(f"dimensions must be from {MIN_DIMENSIONS} to {native_dimensions}")
    return value


def shortened(vector, dimensions):
    head = [float(x) for x in vector[:dimensions]]
    norm = math.sqrt(sum(x * x for x in head))
    if norm == 0:
        return head
    return [x / norm for x in head]


def response_body(served_model, vectors):
    return {
        "object": "list",
        "model": served_model,
        "data": [
            {"object": "embedding", "index": index, "embedding": vector}
            for index, vector in enumerate(vectors)
        ],
    }


class EmbeddingService:
    def __init__(self, served_model, native_dimensions, encode):
        self.served_model = served_model
        self.native_dimensions = native_dimensions
        self._encode = encode
        self._one_at_a_time = threading.Lock()

    def embed(self, body):
        request = parse_request(body, self.served_model, self.native_dimensions)
        with self._one_at_a_time:
            vectors = self._encode(request.texts, request.input_type)
        return response_body(
            self.served_model, [shortened(vector, request.dimensions) for vector in vectors]
        )


def handler_for(service):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if self.path == "/healthz":
                self._answer(HTTPStatus.OK, {"status": "ok", "model": service.served_model})
            else:
                self._answer(HTTPStatus.NOT_FOUND, _error("not found"))

        def do_POST(self):
            if self.path != "/v1/embeddings":
                self._answer(HTTPStatus.NOT_FOUND, _error("not found"))
                return
            length = self._content_length()
            if length is None:
                self._answer(HTTPStatus.LENGTH_REQUIRED, _error("Content-Length is required"))
                return
            if length > MAX_BODY_BYTES:
                self.close_connection = True
                self._answer(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, _error("the body is too large"))
                return
            try:
                self._answer(HTTPStatus.OK, service.embed(self.rfile.read(length)))
            except BadRequest as e:
                self._answer(HTTPStatus.BAD_REQUEST, _error(str(e)))
            except Exception:
                self.log_error("embedding failed")
                self._answer(HTTPStatus.INTERNAL_SERVER_ERROR, _error("embedding failed"))

        def _content_length(self):
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                return None
            return length if length >= 0 else None

        def _answer(self, status, payload):
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            if self.path != "/healthz":
                super().log_message(format, *args)

    return Handler


def _error(message):
    return {"object": "error", "message": message}


def server_for(service, host, port):
    return ThreadingHTTPServer((host, port), handler_for(service))
