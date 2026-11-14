package ai.helper2.platform.llm;

public final class UntrustedText {

  private UntrustedText() {}

  public static String untrusted(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    String safe = text.replace("<", "‹").replace(">", "›");
    return safe.length() <= maxChars ? safe : safe.substring(0, maxChars) + "…";
  }
}
