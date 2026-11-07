package ai.helper2.platform.settings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The settings one module shows in the back office (ADR 0026), as a bean. The module that owns the
 * settings builds its group from its own {@code @ConfigurationProperties} records, so the back
 * office reads no other module's record and never the environment.
 *
 * @param id a stable id, the prefix of every setting's key
 * @param title what the page shows above the group
 * @param order the place of the group on the page, lowest first
 */
public record SettingsGroup(String id, String title, int order, List<Setting> settings) {

  public SettingsGroup {
    settings = List.copyOf(settings);
    Set<String> keys = new HashSet<>();
    for (Setting s : settings) {
      if (!s.key().startsWith(id + ".") || !keys.add(s.key())) {
        throw new IllegalArgumentException("Setting key " + s.key() + " in group " + id);
      }
    }
  }

  /** The order of the groups on the page. */
  public static final int MODELS = 10;

  public static final int GUIDE = 20;
  public static final int SEARCH = 30;
  public static final int SCOUT = 40;
  public static final int SCOUT_SOURCES = 50;
  public static final int SETUP = 60;
  public static final int EXAMPLES = 70;
}
