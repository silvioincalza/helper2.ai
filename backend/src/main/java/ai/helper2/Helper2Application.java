package ai.helper2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Helper2Application {

    public static void main(String[] args) {
        SpringApplication.run(Helper2Application.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
