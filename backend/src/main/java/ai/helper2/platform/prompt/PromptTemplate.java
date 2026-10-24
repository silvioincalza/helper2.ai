package ai.helper2.platform.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prompt kept as a UTF-8 text file under {@code prompts/} on the classpath, so its wording changes
 * without touching Java and reviews as a plain-text diff. Values the code enforces (limits, allowed
 * enum values) go into {@code {{name}}} placeholders.
 *
 * <p>Rendering is strict in both directions: a placeholder without a value and a value without a
 * placeholder are both errors. A renamed placeholder therefore fails a test, not a production call
 * that sends "{{maxPicks}}" to the model. Values are inserted once, never re-scanned, so a value that
 * contains "{{x}}" stays literal.
 *
 * <p>Not Spring AI's {@code PromptTemplate}: its StringTemplate syntax ({@code {name}}) collides with
 * the JSON examples in our prompts, and it does not report unused values.
 */
public final class PromptTemplate {

    static final String ROOT = "prompts/";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");

    private final String name;
    private final String text;

    private PromptTemplate(String name, String text) {
        this.name = name;
        this.text = text;
    }

    /** Reads {@code prompts/<name>}, e.g. {@code fromClasspath("guide/plan.txt")}. */
    public static PromptTemplate fromClasspath(String name) {
        String path = ROOT + name;
        try (InputStream in = PromptTemplate.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("prompt not found on the classpath: " + path);
            }
            return new PromptTemplate(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read prompt " + path, e);
        }
    }

    static PromptTemplate of(String name, String text) {
        return new PromptTemplate(name, text);
    }

    /** For a prompt without placeholders. */
    public String render() {
        return render(Map.of());
    }

    public String render(Map<String, ?> values) {
        TreeSet<String> unused = new TreeSet<>(values.keySet());
        Matcher placeholder = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (placeholder.find()) {
            String key = placeholder.group(1);
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("prompt " + name + ": no value for {{" + key + "}}");
            }
            unused.remove(key);
            placeholder.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(values.get(key))));
        }
        placeholder.appendTail(out);
        if (!unused.isEmpty()) {
            throw new IllegalArgumentException("prompt " + name + ": no placeholder for " + unused);
        }
        return out.toString();
    }
}
