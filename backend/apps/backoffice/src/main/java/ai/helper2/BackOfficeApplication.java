package ai.helper2;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The back office (ADR 0032): admin, Scout and the catalog's jobs, on the admin host only. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BackOfficeApplication {

  public static void main(String[] args) {
    SpringApplication.run(BackOfficeApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
