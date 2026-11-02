package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import org.hamcrest.Matcher;
import org.springframework.http.MediaType;
import org.springframework.test.util.JsonPathExpectationsHelper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Asks the guide over {@code POST /api/v1/guide/stream}, the only guide endpoint, and returns the data of its last
 * event, {@code answer}, with JSONPath checks on it.
 */
final class GuideAnswers {

    private static final Pattern ANSWER = Pattern.compile("event:answer\\ndata:(.*)\\n");

    private GuideAnswers() {
    }

    /** The {@code answer} of a question that streams to the end; fails with the whole stream when there is none. */
    static Answer ask(MockMvc mvc, String json, RequestPostProcessor... with) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/guide/stream").contentType(MediaType.APPLICATION_JSON).content(json);
        for (RequestPostProcessor p : with) {
            request = request.with(p);
        }
        MvcResult result = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        // The completed emitter is dispatched again, as on a real servlet container (interceptors run again too).
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String stream = result.getResponse().getContentAsString();
        java.util.regex.Matcher m = ANSWER.matcher(stream);
        assertThat(m.find()).as("event answer in " + stream).isTrue();
        return new Answer(m.group(1));
    }

    /** The answer's JSON, and JSONPath checks on it in the style of MockMvc's {@code jsonPath(...)}. */
    record Answer(String json) {

        @SuppressWarnings("unchecked")
        Answer value(String path, Object expected) {
            if (expected instanceof Matcher<?> matcher) {
                new JsonPathExpectationsHelper(path).assertValue(json, (Matcher<Object>) matcher);
            } else {
                new JsonPathExpectationsHelper(path).assertValue(json, expected);
            }
            return this;
        }

        Answer exists(String path) {
            new JsonPathExpectationsHelper(path).exists(json);
            return this;
        }

        Answer doesNotExist(String path) {
            new JsonPathExpectationsHelper(path).doesNotExist(json);
            return this;
        }
    }
}
