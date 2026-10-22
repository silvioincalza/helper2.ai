package ai.helper2.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only what the client sent is a 400; a bug that happens to throw IllegalArgumentException is a 500. */
class ApiExceptionHandlerTest {

    @RestController
    static class Failing {
        @GetMapping("/bad-input")
        String badInput() {
            throw new BadRequestException("query must not be blank");
        }

        @GetMapping("/bug")
        String bug() {
            throw new IllegalArgumentException("internal detail that must not leak");
        }

        @GetMapping("/missing")
        String missing() {
            throw new NotFoundException("Resource not found.");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Failing())
        .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void badRequestExceptionIsA400() throws Exception {
        mvc.perform(get("/bad-input"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void anyOtherExceptionIsA500WithoutItsMessage() throws Exception {
        mvc.perform(get("/bug"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Something went wrong on our side."));
    }

    @Test
    void notFoundKeepsItsMessage() throws Exception {
        mvc.perform(get("/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Resource not found."));
    }
}
