package ai.helper2.platform.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptTemplate {

  static final String ROOT = "prompts/";

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");

  private final String name;
  private final String text;

  private PromptTemplate(String name, String text) {
    this.name = name;
    this.text = text;
  }

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
