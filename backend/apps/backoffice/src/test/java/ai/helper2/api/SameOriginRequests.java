package ai.helper2.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

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
