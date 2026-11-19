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
import org.jspecify.annotations.Nullable;

final class AwesomeListParser {
  record Entry(
      String fullName, String section, String headingPath, String text, boolean inListOfLists) {

    boolean pointsAtList() {
      String name = fullName.substring(fullName.indexOf('/') + 1).toLowerCase(Locale.ROOT);
      return name.contains("awesome") || inListOfLists;
    }
  }

  record Link(String url, String text, String section, String headingPath, String entryText) {}

  record Parsed(
      List<Entry> entries,
      List<String> headings,
      List<Link> links,
      int textLines,
      int linkedItems) {

    List<Entry> searchEntries(int maxEntries, int maxBytes) {
      return searchEntries(new SearchBudget(maxEntries, maxBytes));
    }

    List<Entry> searchEntries(SearchBudget budget) {
      List<Entry> kept = new ArrayList<>();
      for (Entry e : entries) {
        if (budget.spent(e.text(), e.headingPath())) {
          break;
        }
        if (budget.keeps(e.text(), e.headingPath())) {
          kept.add(e);
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

  static final class SearchBudget {

    private final int maxEntries;
    private final int maxBytes;
    private int entries;
    private int bytes;

    SearchBudget(int maxEntries, int maxBytes) {
      this.maxEntries = maxEntries;
      this.maxBytes = maxBytes;
    }

    boolean spent(String text, String headingPath) {
      return entries >= maxEntries || bytes + size(text, headingPath) > maxBytes;
    }

    boolean keeps(String text, String headingPath) {
      if (spent(text, headingPath) || (text.isEmpty() && headingPath.isEmpty())) {
        return false;
      }
      entries++;
      bytes += size(text, headingPath);
      return true;
    }

    private static int size(String text, String headingPath) {
      return utf8(text) + utf8(headingPath);
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

  static final int MAX_ITEM_LINES = 20;

  private AwesomeListParser() {}

  static List<Entry> entries(String markdown, String listFullName) {
    return parse(markdown, listFullName).entries();
  }

  static Parsed parse(String markdown, String listFullName) {
    Found found = new Found(listFullName.toLowerCase(Locale.ROOT));
    Set<String> headings = new LinkedHashSet<>();
    String[] stack = new String[7];
    Arrays.fill(stack, "");
    boolean fenced = false;
    ListLines lines = new ListLines();
    Item open = null;
    for (String line : markdown.split("\\R")) {
      if (FENCE.matcher(line).find()) {
        fenced = !fenced;
        lines.end();
        open = found.close(open);
        continue;
      }
      if (fenced) {
        continue;
      }
      Matcher heading = HEADING.matcher(line.strip());
      if (heading.matches()) {
        open = found.close(open);
        String text = MarkdownText.cut(MarkdownText.plain(heading.group(2)), MAX_HEADING_PATH);
        enter(stack, heading.group(1).length(), text);
        if (!text.isEmpty()) {
          headings.add(text);
        }
        lines.end();
        continue;
      }
      boolean item = LIST_ITEM.matcher(line).find();
      if (!line.isBlank()) {
        if (open != null && open.owns(line, item)) {
          open.add(line);
        } else {
          found.close(open);
          open = item ? new Item(line, stack) : null;
        }
      }
      lines.add(line, item);
    }
    found.close(open);
    return new Parsed(
        new ArrayList<>(found.entries.values()),
        new ArrayList<>(headings),
        new ArrayList<>(found.links.values()),
        lines.textLines,
        lines.linkedItems);
  }

  private static final class Item {

    private final int indent;
    private final String section;
    private final String headingPath;
    private final boolean inListOfLists;
    private final List<String> lines = new ArrayList<>();

    Item(String line, String[] stack) {
      this.indent = ListLines.indent(line);
      this.section = section(stack);
      this.headingPath = AwesomeListParser.headingPath(stack);
      this.inListOfLists = AwesomeListParser.inListOfLists(stack);
      lines.add(line);
    }

    boolean owns(String line, boolean listItem) {
      return ListLines.indent(line) > indent && !(listItem && ListLines.web(line));
    }

    void add(String line) {
      if (lines.size() < MAX_ITEM_LINES) {
        lines.add(line);
      }
    }

    String text() {
      StringBuilder text = new StringBuilder();
      for (String line : lines) {
        if (text.length() >= MAX_ENTRY_TEXT) {
          break;
        }
        String plain = MarkdownText.plain(line);
        if (!plain.isEmpty()) {
          text.append(text.isEmpty() ? "" : " ").append(plain);
        }
      }
      return MarkdownText.cut(text.toString(), MAX_ENTRY_TEXT);
    }
  }

  private static final class Found {

    private final String self;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Link> links = new LinkedHashMap<>();

    Found(String self) {
      this.self = self;
    }

    @Nullable Item close(@Nullable Item item) {
      if (item == null) {
        return null;
      }
      String text = item.text();
      for (String line : item.lines) {
        addEntries(line, item, text);
        addLinks(line, item, text);
      }
      return null;
    }

    private void addEntries(String line, Item item, String text) {
      Matcher m = REPO_LINK.matcher(line);
      while (m.find()) {
        String owner = m.group(1);
        String name = cleanName(m.group(2));
        if (name.isEmpty() || NOT_OWNERS.contains(owner.toLowerCase(Locale.ROOT))) {
          continue;
        }
        String fullName = owner + "/" + name;
        String key = fullName.toLowerCase(Locale.ROOT);
        if (!key.equals(self)) {
          entries.putIfAbsent(
              key, new Entry(fullName, item.section, item.headingPath, text, item.inListOfLists));
        }
      }
    }

    private void addLinks(String line, Item item, String text) {
      Matcher m = MARKDOWN_LINK.matcher(MarkdownText.cut(line, MarkdownText.MAX_RAW));
      while (m.find() && links.size() < MAX_LINKS) {
        String url = m.group(2);
        if (url.indexOf(':') < 0 || GITHUB_HOST.matcher(url).find()) {
          continue;
        }
        String words = MarkdownText.cut(MarkdownText.plain(m.group(1)), MAX_LINK_TEXT);
        links.putIfAbsent(url, new Link(url, words, item.section, item.headingPath, text));
      }
    }
  }

  static final class ListLines {

    private static final Pattern HTML_ITEM = Pattern.compile("(?i)^\\s*<li\\b");
    private static final Pattern IMAGE =
        Pattern.compile("!\\[[^\\]]{0,300}\\]\\([^)]{0,2000}\\)|(?i)<img\\b[^>]{0,2000}>");
    private static final Pattern LINKED_IMAGE =
        Pattern.compile(
            "\\[\\s*(?:!\\[[^\\]]{0,300}\\]\\([^)]{0,2000}\\)|(?i)<img\\b[^>]{0,2000}>)\\s*\\]\\([^)]{0,2000}\\)");
    private static final Pattern WEB = Pattern.compile("(?i)https?://");

    private int textLines;
    private int linkedItems;
    private boolean inItem;
    private boolean itemLinked;
    private int itemIndent;

    void add(String line, boolean listItem) {
      if (line.isBlank()) {
        return;
      }
      boolean item = listItem || HTML_ITEM.matcher(line).find();
      boolean web = web(line);
      int indent = indent(line);
      if (inItem && indent > itemIndent && !(item && web)) {
        continueItem(web);
        return;
      }
      if (item) {
        startItem(indent, web);
        return;
      }
      end();
      if (!MarkdownText.plain(line).isBlank()) {
        textLines++;
      }
    }

    void end() {
      inItem = false;
    }

    private void startItem(int indent, boolean web) {
      inItem = true;
      itemIndent = indent;
      itemLinked = web;
      textLines++;
      if (web) {
        linkedItems++;
      }
    }

    private void continueItem(boolean web) {
      if (web && !itemLinked) {
        itemLinked = true;
        linkedItems++;
      }
    }

    private static boolean web(String line) {
      String cut = MarkdownText.cut(line, MarkdownText.MAX_RAW);
      String noBadges = LINKED_IMAGE.matcher(cut).replaceAll(" ");
      return WEB.matcher(IMAGE.matcher(noBadges).replaceAll(" ")).find();
    }

    private static int indent(String line) {
      int n = 0;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        if (c == ' ') {
          n++;
        } else if (c == '\t') {
          n += 4;
        } else {
          break;
        }
      }
      return n;
    }
  }

  private static void enter(String[] stack, int level, String text) {
    stack[level] = text;
    for (int deeper = level + 1; deeper < stack.length; deeper++) {
      stack[deeper] = "";
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
