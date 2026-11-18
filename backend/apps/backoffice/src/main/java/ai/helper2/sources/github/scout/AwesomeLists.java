package ai.helper2.sources.github.scout;

import java.util.Locale;

public final class AwesomeLists {

  static final int MIN_LINKED_ITEMS = 20;

  static final double MIN_LIST_SHARE = 0.5;

  private AwesomeLists() {}

  public static boolean isAwesomeList(String fullName, String readme) {
    return isAwesomeList(fullName, AwesomeListParser.parse(readme, fullName));
  }

  static boolean isAwesomeList(String fullName, AwesomeListParser.Parsed readme) {
    return named(fullName)
        && readme.linkedItems() >= MIN_LINKED_ITEMS
        && readme.linkedItems() >= MIN_LIST_SHARE * readme.textLines();
  }

  static boolean named(String fullName) {
    String name = fullName.substring(fullName.indexOf('/') + 1).toLowerCase(Locale.ROOT);
    return name.startsWith("awesome");
  }
}
