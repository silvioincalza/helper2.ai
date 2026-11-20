import http.client
import json
import math
import threading
import unittest
import urllib.error
import urllib.request

from embedding_api import (
    MAX_BODY_BYTES,
    MAX_INPUTS,
    BadRequest,
    EmbeddingService,
    parse_request,
    server_for,
    shortened,
)

MODEL = "nvidia/nemotron-3.5-embed-1b"
NATIVE = 2048


def body(**fields):
    request = {"model": MODEL, "input": ["a tool"], "input_type": "passage", "dimensions": 1024}
    request.update(fields)
    return json.dumps(request).encode()


class ParseRequestTest(unittest.TestCase):
    def test_reads_the_call_the_application_sends(self):
        request = parse_request(
            body(input_type="query", truncate="END", encoding_format="float"), MODEL, NATIVE
        )
        self.assertEqual(request.texts, ["a tool"])
        self.assertEqual(request.input_type, "query")
        self.assertEqual(request.dimensions, 1024)

    def test_one_text_is_a_list_of_one(self):
        self.assertEqual(parse_request(body(input="a tool"), MODEL, NATIVE).texts, ["a tool"])

    def test_without_dimensions_the_vector_keeps_its_native_size(self):
        self.assertEqual(parse_request(body(dimensions=None), MODEL, NATIVE).dimensions, NATIVE)

    def test_refuses_what_it_cannot_embed(self):
        refused = {
            "not JSON": b"{",
            "not an object": b"[]",
            "another model": body(model="text-embedding-3-small"),
            "no input type": body(input_type=None),
            "an unknown input type": body(input_type="document"),
            "no input": body(input=[]),
            "an empty text": body(input=["a", ""]),
            "a number as text": body(input=[1]),
            "too many texts": body(input=["a"] * (MAX_INPUTS + 1)),
            "too few dimensions": body(dimensions=64),
            "too many dimensions": body(dimensions=NATIVE + 1),
            "dimensions as text": body(dimensions="1024"),
            "dimensions as a flag": body(dimensions=True),
        }
        for name, payload in refused.items():
            with self.subTest(name):
                with self.assertRaises(BadRequest):
                    parse_request(payload, MODEL, NATIVE)


class ShortenedTest(unittest.TestCase):
    def test_keeps_the_first_dimensions_and_normalises_again(self):
        vector = shortened([3.0, 4.0, 12.0], 2)
        self.assertEqual(len(vector), 2)
        self.assertAlmostEqual(vector[0], 0.6)
        self.assertAlmostEqual(vector[1], 0.8)
        self.assertAlmostEqual(math.sqrt(sum(x * x for x in vector)), 1.0)

    def test_a_zero_vector_stays_zero(self):
        self.assertEqual(shortened([0.0, 0.0, 1.0], 2), [0.0, 0.0])


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.calls = []

        def encode(texts, input_type):
            self.calls.append((texts, input_type))
            if texts == ["boom"]:
                raise RuntimeError("model failure")
            return [[float(i + 1)] * NATIVE for i in range(len(texts))]

        self.server = server_for(EmbeddingService(MODEL, NATIVE, encode), "127.0.0.1", 0)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def post(self, payload, path="/v1/embeddings"):
        request = urllib.request.Request(
            self.base + path, payload, {"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_answers_one_vector_per_text_in_order_at_the_asked_size(self):
        status, answer = self.post(body(input=["a", "b"], input_type="query"))
        self.assertEqual(status, 200)
        self.assertEqual(self.calls, [(["a", "b"], "query")])
        self.assertEqual([item["index"] for item in answer["data"]], [0, 1])
        for item in answer["data"]:
            self.assertEqual(len(item["embedding"]), 1024)
            self.assertAlmostEqual(math.sqrt(sum(x * x for x in item["embedding"])), 1.0)

    def test_a_refused_request_is_400_and_never_reaches_the_model(self):
        status, answer = self.post(body(input_type=None))
        self.assertEqual(status, 400)
        self.assertEqual(answer["message"], "input_type must be query or passage")
        self.assertEqual(self.calls, [])

    def test_a_model_failure_is_500_without_its_detail(self):
        status, answer = self.post(body(input=["boom"]))
        self.assertEqual(status, 500)
        self.assertEqual(answer["message"], "embedding failed")

    def test_a_body_over_the_limit_is_413_and_never_read(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=5)
        connection.putrequest("POST", "/v1/embeddings")
        connection.putheader("Content-Length", str(MAX_BODY_BYTES + 1))
        connection.endheaders()
        self.assertEqual(connection.getresponse().status, 413)
        connection.close()
        self.assertEqual(self.calls, [])

    def test_another_path_is_404(self):
        self.assertEqual(self.post(body(), "/v1/chat/completions")[0], 404)

    def test_health_names_the_model(self):
        with urllib.request.urlopen(self.base + "/healthz", timeout=5) as response:
            self.assertEqual(json.load(response), {"status": "ok", "model": MODEL})


if __name__ == "__main__":
    unittest.main()
