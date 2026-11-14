package ai.helper2.platform.http;

import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class HttpClientSettingsConfiguration {

  @Bean
  SettingsGroup outboundHttpSettings(HttpClientProperties http) {
    return outboundHttpSettingsGroup(http);
  }

  static SettingsGroup outboundHttpSettingsGroup(HttpClientProperties http) {
    return new SettingsGroup(
        "http",
        "Outbound HTTP",
        SettingsGroup.HTTP,
        List.of(
            Setting.fixedCount(
                "http.poolWait", "Wait for a free connection", http.poolWait().toMillis(), "ms"),
            Setting.fixedCount(
                "http.maxConnections",
                "Connections of one client",
                http.maxConnections(),
                "connections"),
            Setting.fixedCount(
                "http.maxConnectionsPerRoute",
                "Connections to one host",
                http.maxConnectionsPerRoute(),
                "connections"),
            Setting.fixedCount(
                "http.maxResponseSize",
                "Largest response body",
                http.maxResponseSize().toKilobytes(),
                "KB")));
  }
}
