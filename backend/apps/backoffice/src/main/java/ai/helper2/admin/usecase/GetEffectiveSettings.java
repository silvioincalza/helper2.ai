package ai.helper2.admin.usecase;

import ai.helper2.platform.settings.PublicAppSettings;
import ai.helper2.platform.settings.PublicAppSettings.Publication;
import ai.helper2.platform.settings.SettingsGroup;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GetEffectiveSettings {

  public enum App {
    BACK_OFFICE,
    PUBLIC
  }

  public record AppGroup(App app, SettingsGroup group) {}

  public record EffectiveSettings(List<AppGroup> groups, Optional<Publication> publicApp) {}

  private static final Comparator<AppGroup> PAGE_ORDER =
      Comparator.comparingInt((AppGroup g) -> g.group().order()).thenComparing(g -> g.group().id());

  private final List<SettingsGroup> own;
  private final Set<String> ownIds;
  private final PublicAppSettings publicApp;

  public GetEffectiveSettings(List<SettingsGroup> own, PublicAppSettings publicApp) {
    this.own = List.copyOf(own);
    this.ownIds = own.stream().map(SettingsGroup::id).collect(Collectors.toUnmodifiableSet());
    this.publicApp = publicApp;
  }

  public EffectiveSettings get() {
    Optional<Publication> publication = publicApp.newest();
    Stream<AppGroup> ownGroups = own.stream().map(g -> new AppGroup(App.BACK_OFFICE, g));
    Stream<AppGroup> publicOnly =
        publication.stream()
            .flatMap(p -> p.groups().stream())
            .filter(g -> !ownIds.contains(g.id()))
            .map(g -> new AppGroup(App.PUBLIC, g));
    return new EffectiveSettings(
        Stream.concat(ownGroups, publicOnly).sorted(PAGE_ORDER).toList(), publication);
  }
}
