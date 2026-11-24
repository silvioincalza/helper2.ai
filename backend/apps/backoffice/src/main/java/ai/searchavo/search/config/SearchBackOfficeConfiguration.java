package ai.searchavo.search.config;

import ai.searchavo.platform.AuditTrail;
import ai.searchavo.search.DescribedSource;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.search.ShadowLibraryStore;
import ai.searchavo.search.usecase.ManageShadowLibraries;
import ai.searchavo.shared.UnitOfWork;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.NamedInterface;

@NamedInterface("backoffice")
public class SearchBackOfficeConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SearchBackOfficeConfiguration.class);

  @Bean
  ManageShadowLibraries manageShadowLibraries(
      ShadowLibraryStore store,
      ShadowLibraries libraries,
      AuditTrail audit,
      UnitOfWork unitOfWork,
      Clock clock,
      List<DescribedSource> sources) {
    ManageShadowLibraries manage =
        new ManageShadowLibraries(store, libraries, audit, unitOfWork, clock, hostsOf(sources));
    warnOfNamesThatHideASource(manage);
    return manage;
  }

  private static void warnOfNamesThatHideASource(ManageShadowLibraries manage) {
    try {
      manage
          .namesThatHideASource()
          .forEach(name ->
              log.warn(
                  "shadow library name {} is a label of a source's host: that source's results"
                      + " are hidden",
                  name));
    } catch (RuntimeException e) {
      log.warn(
          "shadow library names not checked against the sources: {}", e.getClass().getSimpleName());
    }
  }

  private static List<String> hostsOf(List<DescribedSource> sources) {
    return sources.stream()
        .map(s -> s.getDescriptor().getHomepage().getHost())
        .filter(Objects::nonNull)
        .toList();
  }
}
