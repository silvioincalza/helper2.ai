package ai.helper2.platform.settings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

  public static final int MODELS = 10;

  public static final int GUIDE = 20;
  public static final int SEARCH = 30;
  public static final int RANKING = 35;
  public static final int SCOUT = 40;
  public static final int SCOUT_SOURCES = 50;
  public static final int SETUP = 60;
  public static final int EXAMPLES = 70;
  public static final int HTTP = 80;
}
