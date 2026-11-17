package ai.helper2.sources.support;

import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WordLists;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Classification {

  private static final WordLists WORDS = WordLists.load("ai-words");

  private static final Pattern STRONG = terms("ai.strong");

  private static final Pattern WEAK = terms("ai.weak");

  private static final int WEAK_WORDS_NEEDED = 2;

  private static Pattern terms(String key) {
    return Pattern.compile(
        "(?<![\\p{L}\\p{N}])(?:"
            + WordLists.alternation(WORDS.phrases(key))
            + ")\\d*(?![\\p{L}\\p{N}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private static final Set<String> OSI =
      Set.of(
          "MIT",
          "Apache-2.0",
          "BSD-2-Clause",
          "BSD-3-Clause",
          "GPL-2.0",
          "GPL-3.0",
          "LGPL-2.1",
          "LGPL-3.0",
          "AGPL-3.0",
          "MPL-2.0",
          "ISC",
          "Unlicense",
          "EPL-2.0",
          "0BSD",
          "Zlib",
          "Artistic-2.0");

  private Classification() {}

  public static boolean isAiRelated(String title, String description, Collection<String> labels) {
    String text = text(title, description, labels);
    return STRONG.matcher(text).find() || aiWords(text) >= WEAK_WORDS_NEEDED;
  }

  private static String text(String title, String description, Collection<String> labels) {
    return String.join(
        " ",
        title == null ? "" : title.replace('-', ' ').replace('_', ' '),
        description == null ? "" : description,
        String.join(" ", labels).replace('-', ' '));
  }

  private static long aiWords(String text) {
    return Stream.concat(found(STRONG, text), found(WEAK, text)).distinct().count();
  }

  private static Stream<String> found(Pattern terms, String text) {
    return terms.matcher(text).results().map(m -> stem(m.group()));
  }

  private static String stem(String word) {
    return word.toLowerCase(Locale.ROOT).replaceAll("\\d+$", "").replaceAll("s$", "");
  }

  public static ResourceType typeOf(
      String title, String description, Collection<String> labels, ResourceType fallback) {
    String all =
        (String.join(" ", labels) + " " + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
    String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);
    if (all.contains("mcp-server")
        || all.contains("mcp")
        || all.contains("model-context-protocol")
        || all.contains("modelcontextprotocol")
        || desc.contains("model context protocol")
        || desc.contains("mcp server")) {
      return ResourceType.MCP_SERVER;
    }
    if (all.contains("agent-skill") || all.contains("claude-skills") || all.contains("skills")) {
      return ResourceType.SKILL;
    }
    if (all.contains("prompt") && aiWords(all + " " + desc) >= WEAK_WORDS_NEEDED) {
      return ResourceType.PROMPT;
    }
    if (all.contains("agent")) {
      return ResourceType.AGENT;
    }
    return fallback;
  }

  public static Boolean openSource(String spdx) {
    return spdx != null && OSI.contains(spdx) ? Boolean.TRUE : null;
  }
}
