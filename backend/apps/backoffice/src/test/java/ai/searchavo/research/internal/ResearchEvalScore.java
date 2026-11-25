package ai.searchavo.research.internal;

import ai.searchavo.research.ResearchResult;
import ai.searchavo.research.ResearchRun;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class ResearchEvalScore {

  static final int TARGET_ITEMS = 100;
  static final int TARGET_REJECTS = 20;

  private static final String DUPLICATE = "DUPLICATE";

  record Outcome(
      ResearchLabels.Label label,
      ResearchRun.Status status,
      @Nullable ResearchResult result,
      @Nullable String error,
      long millis,
      int modelCalls,
      int promptTokens,
      int completionTokens) {}

  record Agreement(int agree, int compared) {
    double share() {
      return compared == 0 ? 0 : (double) agree / compared;
    }
  }

  record Report(
      String model,
      int items,
      int rejects,
      int failed,
      Agreement verdict,
      Map<String, Agreement> attributes,
      Agreement confirmedRight,
      long p50Millis,
      long p95Millis,
      double modelCallsPerItem,
      double tokensPerItem,
      List<Row> rows) {}

  record Row(String id, String url, String decision, String verdict, String detail) {}

  private ResearchEvalScore() {}

  private static boolean isMeasuredVerdict(ResearchLabels.Label label) {
    return !DUPLICATE.equals(label.decision());
  }

  static Report score(String model, List<Outcome> outcomes) {
    int failed = 0;
    int verdictAgree = 0;
    int verdictCompared = 0;
    int confirmedAgree = 0;
    int confirmedCompared = 0;
    Map<String, int[]> attributes = new LinkedHashMap<>();
    ResearchLabels.ATTRIBUTES.forEach(a -> attributes.put(a, new int[2]));
    List<Row> rows = new ArrayList<>();
    for (Outcome o : outcomes) {
      ResearchLabels.Label label = o.label();
      if (o.status() != ResearchRun.Status.DONE || o.result() == null) {
        failed++;
        rows.add(new Row(label.id(), label.url(), label.decision(), "FAILED", nn(o.error())));
        continue;
      }
      ResearchResult r = o.result();
      if (isMeasuredVerdict(label)) {
        verdictCompared++;
        if (r.verdict().name().equals(label.decision())) {
          verdictAgree++;
        }
      }
      List<String> misses = new ArrayList<>();
      for (Map.Entry<String, String> e : label.expected().entrySet()) {
        ResearchResult.Attribute attribute = attribute(r, e.getKey());
        if (attribute == null || attribute.proposed().isEmpty()) {
          continue;
        }
        boolean right =
            attribute.proposed().stream().anyMatch(v -> same(e.getKey(), v, e.getValue()));
        int[] counts = attributes.get(e.getKey());
        counts[1]++;
        if (right) {
          counts[0]++;
        } else {
          misses.add(e.getKey());
        }
        if (attribute.status() == ResearchResult.Status.CONFIRMED) {
          confirmedCompared++;
          if (right) {
            confirmedAgree++;
          }
        }
      }
      rows.add(
          new Row(
              label.id(),
              label.url(),
              label.decision(),
              r.verdict().name(),
              misses.isEmpty() ? "" : "differs: " + String.join(", ", misses)));
    }
    Map<String, Agreement> perAttribute = new LinkedHashMap<>();
    attributes.forEach((k, v) -> perAttribute.put(k, new Agreement(v[0], v[1])));
    List<Long> millis =
        outcomes.stream().map(Outcome::millis).sorted(Comparator.naturalOrder()).toList();
    int n = outcomes.size();
    return new Report(
        model,
        n,
        (int) outcomes.stream().filter(o -> o.label().decision().equals("REJECT")).count(),
        failed,
        new Agreement(verdictAgree, verdictCompared),
        perAttribute,
        new Agreement(confirmedAgree, confirmedCompared),
        percentile(millis, 50),
        percentile(millis, 95),
        n == 0 ? 0 : outcomes.stream().mapToInt(Outcome::modelCalls).sum() / (double) n,
        n == 0
            ? 0
            : outcomes.stream().mapToLong(o -> (long) o.promptTokens() + o.completionTokens()).sum()
                / (double) n,
        List.copyOf(rows));
  }

  static boolean same(String attribute, String proposed, String expected) {
    String p = proposed.strip();
    String e = expected.strip();
    return switch (attribute) {
      case "canonicalUrl" -> url(p).equals(url(e));
      case "title", "license" -> p.equalsIgnoreCase(e);
      case "openSource" -> p.equalsIgnoreCase(e.equals("t") ? "true" : e.equals("f") ? "false" : e);
      default -> p.equals(e);
    };
  }

  static String url(String u) {
    String s =
        u.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  static String markdown(Report r) {
    StringBuilder out = new StringBuilder();
    out.append("# Review research eval: ").append(r.model()).append("\n\n");
    if (r.items() < TARGET_ITEMS || r.rejects() < TARGET_REJECTS) {
      out.append("Too few labels for a decision: ")
          .append(r.items())
          .append(" items, ")
          .append(r.rejects())
          .append(" rejects. The bar is ")
          .append(TARGET_ITEMS)
          .append(" items with at least ")
          .append(TARGET_REJECTS)
          .append(" rejects.\n\n");
    }
    out.append("| Measure | Value |\n|---|---|\n");
    out.append("| Items | ")
        .append(r.items())
        .append(" (")
        .append(r.rejects())
        .append(" rejects) |\n");
    out.append("| Failed runs | ").append(r.failed()).append(" |\n");
    out.append("| Verdict agrees with the admin | ").append(share(r.verdict())).append(" |\n");
    r.attributes()
        .forEach((k, v) ->
            out.append("| ").append(k).append(" right | ").append(share(v)).append(" |\n"));
    out.append("| CONFIRMED values that are right | ")
        .append(share(r.confirmedRight()))
        .append(" |\n");
    out.append("| Time per item, p50 / p95 | ")
        .append(r.p50Millis() / 1000.0)
        .append(" s / ")
        .append(r.p95Millis() / 1000.0)
        .append(" s |\n");
    out.append("| Model calls per item | ")
        .append(String.format(Locale.ROOT, "%.1f", r.modelCallsPerItem()))
        .append(" |\n");
    out.append("| Tokens per item | ")
        .append(String.format(Locale.ROOT, "%.0f", r.tokensPerItem()))
        .append(" |\n\n");
    out.append("| Id | Admin | Agent | Note | Item |\n|---|---|---|---|---|\n");
    for (Row row : r.rows()) {
      out.append("| ")
          .append(row.id())
          .append(" | ")
          .append(row.decision())
          .append(" | ")
          .append(row.verdict())
          .append(" | ")
          .append(row.detail().replace("|", "/"))
          .append(" | ")
          .append(row.url())
          .append(" |\n");
    }
    return out.toString();
  }

  static String json(Report r) {
    StringBuilder out = new StringBuilder("{\n");
    out.append("  \"model\": \"").append(r.model()).append("\",\n");
    out.append("  \"items\": ").append(r.items()).append(",\n");
    out.append("  \"rejects\": ").append(r.rejects()).append(",\n");
    out.append("  \"failed\": ").append(r.failed()).append(",\n");
    out.append("  \"verdictAgreement\": ").append(fixed(r.verdict().share())).append(",\n");
    out.append("  \"attributes\": {");
    List<String> parts = new ArrayList<>();
    r.attributes().forEach((k, v) -> parts.add("\"" + k + "\": " + fixed(v.share())));
    out.append(String.join(", ", parts)).append("},\n");
    out.append("  \"confirmedRight\": ").append(fixed(r.confirmedRight().share())).append(",\n");
    out.append("  \"p50Millis\": ").append(r.p50Millis()).append(",\n");
    out.append("  \"p95Millis\": ").append(r.p95Millis()).append(",\n");
    out.append("  \"modelCallsPerItem\": ").append(fixed(r.modelCallsPerItem())).append(",\n");
    out.append("  \"tokensPerItem\": ").append(fixed(r.tokensPerItem())).append("\n}\n");
    return out.toString();
  }

  private static ResearchResult.@Nullable Attribute attribute(ResearchResult r, String name) {
    return r.attributes().stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
  }

  private static long percentile(List<Long> sorted, int p) {
    if (sorted.isEmpty()) {
      return 0;
    }
    int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
  }

  private static String share(Agreement a) {
    return a.compared() == 0
        ? "no data"
        : String.format(
            Locale.ROOT, "%.0f %% (%d of %d)", a.share() * 100, a.agree(), a.compared());
  }

  private static String fixed(double d) {
    return String.format(Locale.ROOT, "%.4f", d);
  }

  private static String nn(@Nullable String s) {
    return s == null ? "" : s;
  }
}
