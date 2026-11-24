package ai.searchavo.sources;

import ai.searchavo.shared.CollectorIds;
import ai.searchavo.sources.config.DiscoveryCategories;
import ai.searchavo.sources.config.FeedProperties;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.env.Environment;

public final class ScoutSourceIds {

  private ScoutSourceIds() {}

  public static Set<String> of(Environment env) {
    Set<String> ids = new TreeSet<>(fixed());
    DiscoveryCategories.bind(env).ids().forEach(id -> ids.add("discovery-" + id));
    FeedProperties feeds = FeedProperties.bind(env);
    feeds.ids().forEach(id -> ids.add(feeds.get(id).sourceId()));
    return ids;
  }

  public static Set<String> fixed() {
    Set<String> ids = new TreeSet<>();
    Arrays.stream(CollectorIds.class.getFields())
        .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
        .forEach(f -> ids.add(constant(f)));
    return ids;
  }

  private static String constant(Field field) {
    try {
      return (String) field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
