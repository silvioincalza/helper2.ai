package ai.helper2;

import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreTestApplication {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
