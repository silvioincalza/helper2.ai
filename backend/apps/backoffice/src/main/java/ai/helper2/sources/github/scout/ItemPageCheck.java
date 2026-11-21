package ai.helper2.sources.github.scout;

import ai.helper2.shared.CanonicalUrl;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * UC-030 BR-013: the check by code that a page is the page of the item a list entry names: the name
 * is in the address of the page, or in its title. Code can say yes; it never says no.
 */
final class ItemPageCheck {

  /** A shorter name ("AI", "Go") matches too many addresses and titles to prove anything. */
  static final int MIN_NAME = 3;

  /** A page states its title in its first lines. */
  static final int TITLE_LINES = 60;

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NOT_A_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{Nd}]+");
  private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.{1,300})$");

  private ItemPageCheck() {}

  /**
   * The name is a whole part of the address: the name of the site (the part of the host before its
   * public suffix: {@code acme} of {@code app.acme.co.uk}), the site written in one word ({@code
   * acme.ai} for "Acme AI"), or a segment of the path. A subdomain names a part of a site ("app",
   * "docs"), not the item. On a host many items share (a repository, a model, a package) only the
   * path counts.
   */
  static boolean nameInAddress(String name, CanonicalUrl page, @Nullable String site) {
    String key = String.join("", words(name));
    if (key.length() < MIN_NAME) {
      return false;
    }
    List<String> parts = new ArrayList<>();
    if (site != null) {
      parts.add(joined(site.substring(0, Math.max(site.indexOf('.'), 0))));
      parts.add(joined(site));
    }
    String address = page.getValue().substring("https://".length());
    int slash = address.indexOf('/');
    if (slash >= 0) {
      Arrays.stream(address.substring(slash + 1).split("/")).forEach(s -> parts.add(joined(s)));
    }
    return parts.contains(key);
  }

  /** The words of the name stand next to each other, in their order, in the title of the page. */
  static boolean nameInTitle(String name, String markdown) {
    List<String> wanted = words(name);
    String title = title(markdown);
    if (title == null || String.join("", wanted).length() < MIN_NAME) {
      return false;
    }
    return Collections.indexOfSubList(words(title), wanted) >= 0;
  }

  /** The first heading of the page: the page reader gives no other title. */
  static @Nullable String title(String markdown) {
    return markdown
        .lines()
        .limit(TITLE_LINES)
        .map(HEADING::matcher)
        .filter(Matcher::matches)
        .map(m -> MarkdownText.plain(m.group(1)))
        .filter(text -> !text.isBlank())
        .findFirst()
        .orElse(null);
  }

  private static String joined(String text) {
    return String.join("", words(text));
  }

  static List<String> words(String text) {
    String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("");
    return Arrays.stream(NOT_A_LETTER_OR_DIGIT.split(plain.toLowerCase(Locale.ROOT)))
        .filter(word -> !word.isEmpty())
        .toList();
  }
}
