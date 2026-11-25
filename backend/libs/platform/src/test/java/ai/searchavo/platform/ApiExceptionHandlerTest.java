package ai.searchavo.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

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

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Failing())
          .setControllerAdvice(new ApiExceptionHandler())
          .build();

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
    mvc.perform(get("/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Resource not found."));
  }

  @Test
  void aClientThatLeftBeforeTheAnswerIsNoWarning() {
    List<Level> logged = levelsLoggedWhenTheWriteFails(new IOException("Broken pipe"));

    assertThat(logged).doesNotContain(Level.WARN);
  }

  @Test
  void anAnswerThatCouldNotBeWrittenForAnotherReasonIsAWarning() {
    List<Level> logged = levelsLoggedWhenTheWriteFails(new IllegalStateException("no serializer"));

    assertThat(logged).contains(Level.WARN);
  }

  private static List<Level> levelsLoggedWhenTheWriteFails(Exception cause) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger handlerLog = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    handlerLog.addAppender(appender);
    MockHttpServletResponse alreadySent = new MockHttpServletResponse();
    alreadySent.setCommitted(true);
    try {
      new ApiExceptionHandler()
          .handleHttpMessageNotWritable(
              new HttpMessageNotWritableException("Could not write JSON", cause),
              new HttpHeaders(),
              HttpStatus.INTERNAL_SERVER_ERROR,
              new ServletWebRequest(new MockHttpServletRequest(), alreadySent));
    } finally {
      handlerLog.detachAppender(appender);
    }
    return appender.list.stream().map(ILoggingEvent::getLevel).toList();
  }
}
