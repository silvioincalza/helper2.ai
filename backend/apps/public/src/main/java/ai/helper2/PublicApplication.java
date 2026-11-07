package ai.helper2;

import ai.helper2.platform.schema.MigrateStep;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PublicApplication {

  /** The app, or with {@code migrate} the migrate step of a deployment (ADR 0070). */
  public static void main(String[] args) throws Exception {
    if (MigrateStep.requested(args)) {
      MigrateStep.run(args);
      return;
    }
    SpringApplication.run(PublicApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
