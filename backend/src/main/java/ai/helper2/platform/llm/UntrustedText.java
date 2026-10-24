package ai.helper2.platform.llm;

/**
 * Third-party text (search results, source descriptions) sent to a model between our own tags. Angle brackets
 * become {@code ‹ ›}, so the text cannot close a delimiter or open a new one; the validators map them back
 * before they match a quote against the original text.
 */
public final class UntrustedText {

    private UntrustedText() {
    }

    /** The text with {@code < >} replaced, cut to {@code maxChars} (an ellipsis marks the cut); empty for null. */
    public static String untrusted(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String safe = text.replace("<", "‹").replace(">", "›");
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars) + "…";
    }
}
