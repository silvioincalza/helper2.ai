package ai.helper2.research;

import ai.helper2.research.ResearchAnswer.Cite;
import ai.helper2.research.ResearchAnswer.Claim;
import ai.helper2.research.ResearchResult.Attribute;
import ai.helper2.research.ResearchResult.Source;
import ai.helper2.research.ResearchResult.Status;
import ai.helper2.scout.EvidenceRules;
import ai.helper2.scout.EvidenceValidator;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class CheckResearch {

  public static final List<String> ATTRIBUTES =
      List.of(
          "canonicalUrl",
          "title",
          "type",
          "audience",
          "techLevel",
          "pricing",
          "openSource",
          "license",
          "description",
          "tags",
          "language",
          "publishedAt");

  public static final int MIN_TAG_CHARS = 2;
  public static final int MAX_TAG_CHARS = 60;

  private static final Set<String> STATED = Set.of("type", "pricing", "openSource", "license");

  private static final Set<String> REGISTRY_TOOLS =
      Set.of("find_package", "github_repo", "huggingface");

  private static final Set<String> REGISTRY_ATTRIBUTES = Set.of("license", "openSource");

  private static final Set<String> OWN_SITE_ATTRIBUTES =
      Set.of("canonicalUrl", "title", "pricing", "description", "type", "language", "publishedAt");

  private static final Map<String, Integer> SHARED_HOSTS =
      Map.ofEntries(
          Map.entry("github.com", 2),
          Map.entry("gitlab.com", 2),
          Map.entry("codeberg.org", 2),
          Map.entry("bitbucket.org", 2),
          Map.entry("sourceforge.net", 2),
          Map.entry("huggingface.co", 2),
          Map.entry("pypi.org", 2),
          Map.entry("npmjs.com", 2),
          Map.entry("crates.io", 2),
          Map.entry("pkg.go.dev", 3),
          Map.entry("medium.com", 1),
          Map.entry("dev.to", 1),
          Map.entry("x.com", 1),
          Map.entry("twitter.com", 1),
          Map.entry("youtube.com", 1),
          Map.entry("linkedin.com", 2),
          Map.entry("reddit.com", 2));

  private static final Set<String> HUGGING_FACE_KINDS = Set.of("datasets", "spaces");

  private static final Pattern URL = Pattern.compile("^https://[^\\s]{4,2000}$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern LANGUAGE = Pattern.compile("^(simple|[a-z]{2})$");

  private final EvidenceValidator values = new EvidenceValidator();

  public ResearchResult check(
      ResearchSubject subject, ResearchAnswer answer, Map<String, Evidence> evidence) {
    List<String> dropped = new ArrayList<>();
    Map<String, List<Source>> kept = new LinkedHashMap<>();
    Map<String, Set<String>> proposed = new LinkedHashMap<>();
    for (Claim claim : answer.attributes()) {
      String name = claim.attribute();
      String value = claim.value() == null ? "" : claim.value().strip();
      if (!ATTRIBUTES.contains(name)) {
        dropped.add(name + ": not an attribute research proposes");
        continue;
      }
      String valueProblem = valueProblem(name, value);
      if (valueProblem != null) {
        dropped.add(name + " = " + clip(value) + ": " + valueProblem);
        continue;
      }
      for (Cite cite : claim.citations()) {
        Evidence e = evidence.get(cite.evidenceId());
        if (e == null) {
          dropped.add(name + ": unknown evidence id " + clip(cite.evidenceId()));
          continue;
        }
        String quote = EvidenceRules.quoteProblem(cite.excerpt(), e.text());
        if (quote != null) {
          dropped.add(name + " (" + e.id() + "): " + quote);
          continue;
        }
        if (STATED.contains(name)
            && !EvidenceRules.statesValue(cite.excerpt(), field(name), value)) {
          dropped.add(name + " (" + e.id() + "): the excerpt does not state " + clip(value));
          continue;
        }
        kept.computeIfAbsent(name, k -> new ArrayList<>())
            .add(
                new Source(
                    e.id(),
                    e.tool(),
                    e.url(),
                    cite.excerpt().strip(),
                    value,
                    authoritative(name, e, subject.url())));
        proposed.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(value);
      }
    }
    List<Attribute> attributes = new ArrayList<>();
    for (String name : ATTRIBUTES) {
      List<Source> sources = kept.getOrDefault(name, List.of());
      List<String> values = List.copyOf(proposed.getOrDefault(name, Set.of()));
      String current = current(subject, name);
      if (sources.isEmpty() && current == null) {
        continue;
      }
      attributes.add(new Attribute(name, current, values, status(values, sources), sources));
    }
    return new ResearchResult(
        answer.verdict(), answer.reason(), answer.duplicateOf(), attributes, dropped);
  }

  static Status status(List<String> values, List<Source> sources) {
    if (sources.isEmpty()) {
      return Status.NOT_FOUND;
    }
    if (values.size() > 1) {
      return Status.CONFLICT;
    }
    if (sources.stream().anyMatch(Source::authoritative)) {
      return Status.CONFIRMED;
    }
    long hosts = sources.stream().map(s -> host(s.url())).distinct().count();
    return hosts >= 2 ? Status.CONFIRMED : Status.SINGLE_SOURCE;
  }

  private static boolean authoritative(String attribute, Evidence e, String subjectUrl) {
    if (REGISTRY_ATTRIBUTES.contains(attribute) && REGISTRY_TOOLS.contains(e.tool())) {
      return e.recordOf().stream().anyMatch(record -> sameSite(record, subjectUrl));
    }
    return OWN_SITE_ATTRIBUTES.contains(attribute)
        && e.tool().equals("read_page")
        && sameSite(e.url(), subjectUrl);
  }

  static boolean sameSite(@Nullable String a, @Nullable String b) {
    String host = host(withScheme(a));
    if (host == null || !host.equals(host(withScheme(b)))) {
      return false;
    }
    Integer segments = SHARED_HOSTS.get(host);
    if (segments == null) {
      return true;
    }
    List<String> first = segments(withScheme(a));
    List<String> second = segments(withScheme(b));
    if (first == null || second == null) {
      return false;
    }
    int n = segments + (longerOwner(host, first) ? 1 : 0);
    return first.size() >= n
        && second.size() >= n
        && first.subList(0, n).equals(second.subList(0, n));
  }

  private static boolean longerOwner(String host, List<String> segments) {
    return switch (host) {
      case "huggingface.co" ->
          !segments.isEmpty() && HUGGING_FACE_KINDS.contains(segments.getFirst());
      case "npmjs.com" ->
          segments.size() > 1
              && (segments.get(1).startsWith("@") || segments.get(1).startsWith("%40"));
      default -> false;
    };
  }

  private static @Nullable List<String> segments(@Nullable String url) {
    if (url == null) {
      return null;
    }
    String path;
    try {
      URI uri = URI.create(url.strip());
      path = uri.normalize().getRawPath();
      if (path != null && !path.equals(uri.getRawPath())) {
        return null;
      }
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (path == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String segment : path.split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      String s = segment.toLowerCase(Locale.ROOT);
      if (s.equals(".")
          || s.equals("..")
          || s.contains("%2f")
          || s.contains("%5c")
          || s.contains("%2e")
          || s.contains("\\")) {
        return null;
      }
      out.add(s.endsWith(".git") ? s.substring(0, s.length() - 4) : s);
    }
    return out;
  }

  private static @Nullable String withScheme(@Nullable String url) {
    if (url == null || url.isBlank() || url.contains("://")) {
      return url;
    }
    return "https://" + url.strip();
  }

  private @Nullable String valueProblem(String name, String value) {
    if (value.isEmpty()) {
      return "empty value";
    }
    return switch (name) {
      case "type", "audience", "techLevel", "pricing", "openSource", "license" ->
          values.allows(field(name), value) ? null : "not an allowed value";
      case "canonicalUrl" -> URL.matcher(value).matches() ? null : "not an https URL";
      case "title" -> value.length() <= 300 ? null : "longer than 300 characters";
      case "description" -> value.length() <= 4000 ? null : "longer than 4000 characters";
      case "tags" ->
          List.of(value.split(",")).stream().map(String::strip).allMatch(CheckResearch::tagLength)
              ? null
              : "a tag is shorter than "
                  + MIN_TAG_CHARS
                  + " or longer than "
                  + MAX_TAG_CHARS
                  + " characters";
      case "language" -> LANGUAGE.matcher(value).matches() ? null : "not a language code";
      case "publishedAt" -> DATE.matcher(value).matches() ? null : "not a date (YYYY-MM-DD)";
      default -> "not an attribute research proposes";
    };
  }

  private static String field(String attribute) {
    return attribute;
  }

  private static boolean tagLength(String tag) {
    return tag.length() >= MIN_TAG_CHARS && tag.length() <= MAX_TAG_CHARS;
  }

  private static @Nullable String current(ResearchSubject s, String name) {
    return switch (name) {
      case "canonicalUrl" -> s.url();
      case "title" -> s.title();
      case "type" -> s.type();
      case "tags" -> s.tags().isEmpty() ? null : String.join(", ", s.tags());
      default -> null;
    };
  }

  static @Nullable String host(@Nullable String url) {
    if (url == null) {
      return null;
    }
    try {
      String h = URI.create(url.strip()).getHost();
      if (h == null) {
        return null;
      }
      h = h.toLowerCase(Locale.ROOT);
      return h.startsWith("www.") ? h.substring(4) : h;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String clip(String s) {
    return s.length() <= 60 ? s : s.substring(0, 57) + "…";
  }
}
