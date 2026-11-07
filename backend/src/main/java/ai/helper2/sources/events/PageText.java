package ai.helper2.sources.events;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Page text and quotes in one comparable form, so a quote matches the page whatever dash, space,
 * quotation mark or letter case either of them used.
 */
public final class PageText {

  private PageText() {}

  public static String normalize(String text) {
    String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
    s = s.replaceAll("[\\u2010-\\u2015\\u2212]", "-");
    s = s.replaceAll("[\\u201C\\u201D\\u201E\\u00AB\\u00BB]", "\"");
    s = s.replaceAll("[\\u2018\\u2019\\u201A]", "'");
    s = s.replaceAll("[\\s\\u00A0\\u2007\\u202F]+", " ");
    return s.toLowerCase(Locale.ROOT).strip();
  }

  /** True when [quote], normalised, is part of [page], normalised. */
  public static boolean contains(String page, String quote) {
    return normalize(page).contains(normalize(quote));
  }
}
