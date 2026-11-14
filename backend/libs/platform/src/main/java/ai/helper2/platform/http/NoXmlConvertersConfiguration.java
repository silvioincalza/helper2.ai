package ai.helper2.platform.http;

import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NoXmlConvertersConfiguration {

  @Bean
  public ServerHttpMessageConvertersCustomizer noXmlOnTheServer() {
    return builder -> builder.configureMessageConvertersList(XmlConverters::remove);
  }

  @Bean
  public ClientHttpMessageConvertersCustomizer noXmlOnClients() {
    return builder -> builder.configureMessageConvertersList(XmlConverters::remove);
  }
}
