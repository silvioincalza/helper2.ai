package ai.helper2.sources.github;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads an "awesome" README as a link graph: the GitHub repositories linked from list items and
 * table rows, with the heading they sit under. Facts about each repository come from the GitHub
 * API, so Helper2 does not copy the list author's text. For search inside the list (ADR 0055) each
 * entry also carries its line as plain words and its heading path, and the list its headings: the
 * catalog turns them into a tsvector and keeps only that and the heading path, never the text.
 */
final class AwesomeListParser {

  /**
   * One linked repository. [section] is the nearest heading (for the classifier); [headingPath] the
   * headings above it from the second level down ("Tools > Vector databases"), or the title when it
   * sits under the title only; [text] the entry line as plain words.
   */
  record Entry(String fullName, String section, String headingPath, String text) {}

  /** A README: its entries in README order and its headings, each once, in README order. */
  record Parsed(List<Entry> entries, List<String> headings) {

    /**
     * The entries the search index gets: at most [maxEntries], and no more than [maxBytes] of entry
     * text and heading path together (UTF-8). Entries without words are left out.
     */
    List<Entry> searchEntries(int maxEntries, int maxBytes) {
      List<Entry> kept = new ArrayList<>();
      int bytes = 0;
      for (Entry e : entries) {
        if (kept.size() >= maxEntries) {
          break;
        }
        int size = utf8(e.text()) + utf8(e.headingPath());
        if (bytes + size > maxBytes) {
          break;
        }
        if (!e.text().isEmpty() || !e.headingPath().isEmpty()) {
          kept.add(e);
          bytes += size;
        }
      }
      return kept;
    }

    /** The headings one per line, up to {@link #MAX_HEADINGS_BYTES}. */
    String headingsText() {
      StringBuilder out = new StringBuilder();
      int bytes = 0;
      for (String h : headings) {
        int size = utf8(h) + 1;
        if (bytes + size > MAX_HEADINGS_BYTES) {
          break;
        }
        out.append(h).append('\n');
        bytes += size;
      }
      return out.toString();
    }
  }

  /**
   * Owner and name follow GitHub's allowed characters, so they are safe to embed in a GraphQL
   * string.
   */
  private static final Pattern REPO_LINK =
      Pattern.compile("https?://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})");

  private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+|]|\\d+\\.)\\s");
  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.*)$");
  private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

  /** First path segments that are GitHub pages, not users or organizations. */
  private static final Set<String> NOT_OWNERS =
      Set.of(
          "topics",
          "orgs",
          "sponsors",
          "features",
          "marketplace",
          "apps",
          "collections",
          "login",
          "settings",
          "about",
          "site",
          "trending",
          "search",
          "users",
          "readme",
          "contact",
          "pricing",
          "enterprise",
          "github",
          "security",
          "explore",
          "notifications",
          "new",
          "codespaces");

  static final int MAX_SECTION = 80;

  /** One heading, and the heading path built from them (stored as plain text, ADR 0055). */
  static final int MAX_HEADING_PATH = 200;

  /** One entry line as plain words. */
  static final int MAX_ENTRY_TEXT = 500;

  /** All headings of one list together. */
  static final int MAX_HEADINGS_BYTES = 16 * 1024;

  private AwesomeListParser() {}

  /** Unique repositories in README order; the list itself and GitHub's own pages are left out. */
  static List<Entry> entries(String markdown, String listFullName) {
    return parse(markdown, listFullName).entries();
  }

  /**
   * One read of the README: entries and headings; headings and links inside code fences skipped.
   */
  static Parsed parse(String markdown, String listFullName) {
    Map<String, Entry> unique = new LinkedHashMap<>();
    Set<String> headings = new LinkedHashSet<>();
    String[] stack = new String[7];
    Arrays.fill(stack, "");
    String self = listFullName.toLowerCase(Locale.ROOT);
    boolean fenced = false;
    for (String line : markdown.split("\\R")) {
      if (FENCE.matcher(line).find()) {
        fenced = !fenced;
        continue;
      }
      if (fenced) {
        continue;
      }
      Matcher heading = HEADING.matcher(line.strip());
      if (heading.matches()) {
        String text = MarkdownText.cut(MarkdownText.plain(heading.group(2)), MAX_HEADING_PATH);
        enter(stack, heading.group(1).length(), text);
        if (!text.isEmpty()) {
          headings.add(text);
        }
        continue;
      }
      if (LIST_ITEM.matcher(line).find()) {
        addEntries(line, stack, self, unique);
      }
    }
    return new Parsed(new ArrayList<>(unique.values()), new ArrayList<>(headings));
  }

  /** A heading of [level] replaces the one at its level and closes every deeper one. */
  private static void enter(String[] stack, int level, String text) {
    stack[level] = text;
    for (int deeper = level + 1; deeper < stack.length; deeper++) {
      stack[deeper] = "";
    }
  }

  private static void addEntries(
      String line, String[] stack, String self, Map<String, Entry> unique) {
    Matcher m = REPO_LINK.matcher(line);
    String text = null;
    while (m.find()) {
      String owner = m.group(1);
      String name = cleanName(m.group(2));
      if (name.isEmpty() || NOT_OWNERS.contains(owner.toLowerCase(Locale.ROOT))) {
        continue;
      }
      String fullName = owner + "/" + name;
      String key = fullName.toLowerCase(Locale.ROOT);
      if (key.equals(self) || unique.containsKey(key)) {
        continue;
      }
      if (text == null) {
        text = MarkdownText.cut(MarkdownText.plain(line), MAX_ENTRY_TEXT);
      }
      unique.put(key, new Entry(fullName, section(stack), headingPath(stack), text));
    }
  }

  /** The nearest heading above, at any level. */
  private static String section(String[] stack) {
    for (int level = stack.length - 1; level >= 1; level--) {
      if (!stack[level].isEmpty()) {
        return MarkdownText.cut(stack[level], MAX_SECTION);
      }
    }
    return "";
  }

  /** Headings from the second level down; the title alone when there are none. */
  private static String headingPath(String[] stack) {
    String path =
        Arrays.stream(stack, 2, stack.length)
            .filter(h -> !h.isEmpty())
            .collect(Collectors.joining(" > "));
    return MarkdownText.cut(path.isEmpty() ? stack[1] : path, MAX_HEADING_PATH);
  }

  private static String cleanName(String raw) {
    String name = raw.endsWith(".git") ? raw.substring(0, raw.length() - 4) : raw;
    // A sentence-ending dot or ".)" sticks to the match.
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    return name;
  }

  private static int utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }
}
