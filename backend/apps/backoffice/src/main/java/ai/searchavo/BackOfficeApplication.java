package ai.searchavo;

import ai.searchavo.catalog.config.CatalogBackOfficeConfiguration;
import ai.searchavo.examples.config.ExamplesBackOfficeConfiguration;
import ai.searchavo.search.config.SearchBackOfficeConfiguration;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import({
  CatalogBackOfficeConfiguration.class,
  ExamplesBackOfficeConfiguration.class,
  SearchBackOfficeConfiguration.class
})
public class BackOfficeApplication {

  public static void main(String[] args) {
    SpringApplication.run(BackOfficeApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
