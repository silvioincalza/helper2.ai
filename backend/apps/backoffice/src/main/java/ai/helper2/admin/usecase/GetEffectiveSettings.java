package ai.helper2.admin.usecase;

import ai.helper2.platform.settings.SettingsGroup;
import java.util.Comparator;
import java.util.List;

/**
 * The settings the app runs with now (ADR 0026), for the Settings page. Each module publishes its
 * own group from its typed records; this only puts them in page order. The records are bound once
 * at startup, so the list is too.
 */
public class GetEffectiveSettings {

  private final List<SettingsGroup> groups;

  public GetEffectiveSettings(List<SettingsGroup> groups) {
    this.groups =
        groups.stream()
            .sorted(Comparator.comparingInt(SettingsGroup::order).thenComparing(SettingsGroup::id))
            .toList();
  }

  public List<SettingsGroup> get() {
    return groups;
  }
}
