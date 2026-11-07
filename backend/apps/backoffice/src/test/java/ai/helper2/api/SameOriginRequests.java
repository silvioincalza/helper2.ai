package ai.helper2.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MockMvc requests go to http://localhost, with the Host a browser sends and the Origin of the
 * admin host's own pages: the back office refuses a write from any other page (SameOriginWrites). A
 * test that checks a refusal sets its own Origin.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SameOriginRequests {

  @Bean
  MockMvcBuilderCustomizer sameOrigin() {
    return builder ->
        builder.defaultRequest(
            MockMvcRequestBuilders.get("/")
                .header("Host", "localhost")
                .header("Origin", "http://localhost"));
  }
}
