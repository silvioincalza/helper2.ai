package ai.helper2.sources.github.scout;

import java.util.regex.Pattern;

final class MarkdownText {
  private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");

  private static final Pattern IMAGE = Pattern.compile("!\\[[^\\[\\]]*\\]\\([^()]*\\)");
  private static final Pattern LINK = Pattern.compile("\\[([^\\[\\]]*)\\]\\([^()]*\\)");
  private static final Pattern REFERENCE_LINK =
      Pattern.compile("\\[([^\\[\\]]*)\\]\\[[^\\[\\]]*\\]");
  private static final Pattern HTML_TAG = Pattern.compile("<[^<>]*>");
  private static final Pattern HTML_ENTITY = Pattern.compile("&#?[A-Za-z0-9]{1,10};");
  private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S*|\\bwww\\.\\S*");
  private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+\\.)\\s+");
  private static final Pattern MARKERS = Pattern.compile("[*_`~|#>\\[\\]]");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  static final int MAX_RAW = 4_000;

  private MarkdownText() {}

  static String plain(String line) {
    String s = INVISIBLE.matcher(cut(line, MAX_RAW)).replaceAll("");
    s = IMAGE.matcher(s).replaceAll(" ");
    s = LINK.matcher(s).replaceAll("$1");
    s = REFERENCE_LINK.matcher(s).replaceAll("$1");
    s = HTML_TAG.matcher(s).replaceAll(" ");
    s = HTML_ENTITY.matcher(s).replaceAll(" ");
    s = URL.matcher(s).replaceAll(" ");
    s = LIST_MARKER.matcher(s).replaceFirst("");
    s = MARKERS.matcher(s).replaceAll(" ");
    return SPACES.matcher(s).replaceAll(" ").strip();
  }

  static String cut(String text, int max) {
    if (text.codePointCount(0, text.length()) <= max) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, max)).strip();
  }
}
