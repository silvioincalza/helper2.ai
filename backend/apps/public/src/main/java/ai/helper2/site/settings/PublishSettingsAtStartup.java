package ai.helper2.site.settings;

import ai.helper2.platform.config.BuildInfoProperties;
import ai.helper2.platform.settings.PublicAppSettings;
import ai.helper2.platform.settings.SettingsGroup;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class PublishSettingsAtStartup {

  private static final Logger log = LoggerFactory.getLogger(PublishSettingsAtStartup.class);

  private final PublicAppSettings published;
  private final List<SettingsGroup> groups;
  private final String build;
  private final Clock clock;

  PublishSettingsAtStartup(
      PublicAppSettings published,
      List<SettingsGroup> groups,
      BuildInfoProperties build,
      Clock clock) {
    this.published = published;
    this.groups = List.copyOf(groups);
    this.build = build.version();
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  void ready() {
    try {
      published.publish(build, clock.instant(), groups);
    } catch (RuntimeException e) {
      log.warn("settings not published for the back office: {}", e.getClass().getSimpleName());
    }
  }
}
