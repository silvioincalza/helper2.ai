package ai.helper2.sources.github;

import java.util.regex.Pattern;

/**
 * One line of Markdown as plain words for a search index (ADR 0055): link text stays, everything
 * that is not a word a person would search for goes. Images and badges, HTML tags and entities,
 * URLs, Markdown markers, control characters and bidirectional or zero-width characters are
 * removed, so nothing hidden or executable reaches the index.
 */
final class MarkdownText {

  /** Control characters (C0, DEL, C1) and format characters: bidi overrides, zero-width marks. */
  private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");

  private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
  private static final Pattern REFERENCE_LINK = Pattern.compile("\\[([^\\]]*)\\]\\[[^\\]]*\\]");
  private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
  private static final Pattern HTML_ENTITY = Pattern.compile("&#?[A-Za-z0-9]{1,10};");
  private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S*|\\bwww\\.\\S*");
  private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+\\.)\\s+");
  private static final Pattern MARKERS = Pattern.compile("[*_`~|#>\\[\\]]");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  private MarkdownText() {}

  /** The words of [line], single-spaced; empty when nothing is left. */
  static String plain(String line) {
    String s = INVISIBLE.matcher(line).replaceAll("");
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

  /** [text] cut to at most [max] code points, never inside a surrogate pair. */
  static String cut(String text, int max) {
    if (text.codePointCount(0, text.length()) <= max) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, max)).strip();
  }
}
