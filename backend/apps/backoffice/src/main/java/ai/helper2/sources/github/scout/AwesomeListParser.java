package ai.helper2.sources.github.scout;

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

final class AwesomeListParser {
  record Entry(
      String fullName, String section, String headingPath, String text, boolean inListOfLists) {

    Entry(String fullName, String section, String headingPath, String text) {
      this(fullName, section, headingPath, text, false);
    }

    boolean pointsAtList() {
      String name = fullName.substring(fullName.indexOf('/') + 1).toLowerCase(Locale.ROOT);
      return name.contains("awesome") || inListOfLists;
    }
  }

  record Link(String url, String text, String section, String headingPath) {}

  record Parsed(List<Entry> entries, List<String> headings, List<Link> links) {

    Parsed(List<Entry> entries, List<String> headings) {
      this(entries, headings, List.of());
    }

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

  private static final Pattern REPO_LINK =
      Pattern.compile("https?://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})");

  private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+|]|\\d+\\.)\\s");

  private static final Pattern MARKDOWN_LINK =
      Pattern.compile(
          "(?<!!)\\[([^\\[\\]]{0,300})\\]\\(\\s{0,5}<?([^\\s()<>]{1,2000})>?(?:\\s{1,5}\"[^\"]{0,300}\")?\\s{0,5}\\)");

  private static final Pattern GITHUB_HOST =
      Pattern.compile("(?i)^(?:https?:)?//(?:[a-z0-9-]+\\.)*github\\.com(?:[/:?#]|$)");

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.*)$");

  private static final Pattern LIST_OF_LISTS =
      Pattern.compile(
          "(?i)\\b(?:awesome|curated|related|other|more|similar|further)\\b[\\w\\s-]{0,30}?\\blists?\\b"
              + "|\\blists? of lists\\b");

  private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

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

  static final int MAX_HEADING_PATH = 200;

  static final int MAX_ENTRY_TEXT = 500;

  static final int MAX_HEADINGS_BYTES = 16 * 1024;

  static final int MAX_LINK_TEXT = 100;

  static final int MAX_LINKS = 2_000;

  private AwesomeListParser() {}

  static List<Entry> entries(String markdown, String listFullName) {
    return parse(markdown, listFullName).entries();
  }

  static Parsed parse(String markdown, String listFullName) {
    Map<String, Entry> unique = new LinkedHashMap<>();
    Map<String, Link> links = new LinkedHashMap<>();
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
        addLink(line, stack, links);
      }
    }
    return new Parsed(
        new ArrayList<>(unique.values()),
        new ArrayList<>(headings),
        new ArrayList<>(links.values()));
  }

  private static void addLink(String line, String[] stack, Map<String, Link> links) {
    if (links.size() >= MAX_LINKS) {
      return;
    }
    Matcher m = MARKDOWN_LINK.matcher(MarkdownText.cut(line, MarkdownText.MAX_RAW));
    if (!m.find()) {
      return;
    }
    String url = m.group(2);
    if (url.indexOf(':') < 0 || GITHUB_HOST.matcher(url).find() || links.containsKey(url)) {
      return;
    }
    String text = MarkdownText.cut(MarkdownText.plain(m.group(1)), MAX_LINK_TEXT);
    links.put(url, new Link(url, text, section(stack), headingPath(stack)));
  }

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
      unique.put(
          key, new Entry(fullName, section(stack), headingPath(stack), text, inListOfLists(stack)));
    }
  }

  private static String section(String[] stack) {
    for (int level = stack.length - 1; level >= 1; level--) {
      if (!stack[level].isEmpty()) {
        return MarkdownText.cut(stack[level], MAX_SECTION);
      }
    }
    return "";
  }

  private static boolean inListOfLists(String[] stack) {
    return Arrays.stream(stack, 2, stack.length)
        .anyMatch(h -> !h.isEmpty() && LIST_OF_LISTS.matcher(h).find());
  }

  private static String headingPath(String[] stack) {
    String path =
        Arrays.stream(stack, 2, stack.length)
            .filter(h -> !h.isEmpty())
            .collect(Collectors.joining(" > "));
    return MarkdownText.cut(path.isEmpty() ? stack[1] : path, MAX_HEADING_PATH);
  }

  private static String cleanName(String raw) {
    String name = raw.endsWith(".git") ? raw.substring(0, raw.length() - 4) : raw;
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    return name;
  }

  private static int utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }
}
