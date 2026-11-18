package ai.helper2.sources.github.scout;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class AwesomeLists {

  static final int MIN_LINKED_ITEMS = 20;

  static final double MIN_LIST_SHARE = 0.5;

  static final String LIST_TOPIC = "awesome-list";

  private static final List<String> LIST_NAME_PREFIXES = List.of("awesome", "best-of-");

  private AwesomeLists() {}

  public static boolean isAwesomeList(String fullName, String readme) {
    return isAwesomeList(fullName, List.of(), readme);
  }

  public static boolean isAwesomeList(String fullName, Collection<String> topics, String readme) {
    return isAwesomeList(fullName, topics, AwesomeListParser.parse(readme, fullName));
  }

  static boolean isAwesomeList(
      String fullName, Collection<String> topics, AwesomeListParser.Parsed readme) {
    return named(fullName, topics)
        && readme.linkedItems() >= MIN_LINKED_ITEMS
        && readme.linkedItems() >= MIN_LIST_SHARE * readme.textLines();
  }

  static boolean named(String fullName, Collection<String> topics) {
    return named(fullName) || topics.stream().anyMatch(LIST_TOPIC::equalsIgnoreCase);
  }

  static boolean named(String fullName) {
    String name = fullName.substring(fullName.indexOf('/') + 1).toLowerCase(Locale.ROOT);
    return LIST_NAME_PREFIXES.stream().anyMatch(name::startsWith);
  }
}
