package ai.helper2.catalog.internal;

import ai.helper2.catalog.Embedder;
import ai.helper2.platform.http.ObservedHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * NVIDIA API catalog embeddings (OpenAI-compatible {@code POST /v1/embeddings} plus NVIDIA's
 * {@code input_type} and {@code truncate}). Docs: https://docs.nvidia.com/nim/nemo-retriever/embedding/latest/use-the-api-openai.html
 * Nemotron 3.5 Embed accepts any dimension from 128 to 2048 (Matryoshka: first N values, re-normalised);
 * the schema stores 1024, under the 2000 limit of a pgvector HNSW index.
 */
public class NvidiaEmbedder implements Embedder {

    record Request(String model, List<String> input, String input_type, Integer dimensions, String truncate,
                   String encoding_format) {
    }

    record Response(List<Item> data) {
        record Item(int index, float[] embedding) {
        }
    }

    private final RestClient http;
    private final String model;
    private final int dimensions;

    /**
     * Observed like the source calls (ObservedHttp): {@code timeout} to connect, to wait for response data and
     * to wait for a pooled connection; redirects are not followed, so the API key never goes to another host.
     */
    public NvidiaEmbedder(String baseUrl, String apiKey, String model, int dimensions, Duration timeout,
                          ObservationRegistry observations) {
        // Buffered, so the request carries a Content-Length: a streamed (chunked) body of 50 texts made the
        // API catalog hang until the read timeout, while one short query went through (27 Oct 2026).
        var factory = new BufferingClientHttpRequestFactory(ObservedHttp.requestFactory(timeout, timeout, false));
        this.http = ObservedHttp.observed(factory, observations).baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
        if (texts.isEmpty()) {
            return List.of();
        }
        Response response;
        try {
            response = http.post().uri("/embeddings").contentType(MediaType.APPLICATION_JSON)
                .body(new Request(model, texts, kind == Kind.QUERY ? "query" : "passage", dimensions, "END", "float"))
                .retrieve().body(Response.class);
        } catch (RestClientResponseException e) {
            boolean limited = e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
            throw new EmbeddingFailure("embedding call failed: HTTP " + e.getStatusCode().value(), limited);
        } catch (RestClientException e) {
            throw new EmbeddingFailure("embedding call failed: " + e.getClass().getSimpleName(), false);
        }
        return vectors(response, texts.size(), dimensions);
    }

    /** Untrusted shape: every input needs exactly one vector of the expected size. */
    static List<float[]> vectors(Response response, int count, int dimensions) {
        if (response == null || response.data() == null || response.data().size() != count) {
            throw new EmbeddingFailure("embedding response has the wrong number of vectors", false);
        }
        Map<Integer, float[]> byIndex = new LinkedHashMap<>();
        response.data().stream().sorted(Comparator.comparingInt(Response.Item::index))
            .forEach(i -> byIndex.put(i.index(), i.embedding()));
        List<float[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float[] v = byIndex.get(i);
            if (v == null || v.length != dimensions) {
                throw new EmbeddingFailure("embedding response has a missing or wrong-sized vector", false);
            }
            out.add(v);
        }
        return out;
    }
}
