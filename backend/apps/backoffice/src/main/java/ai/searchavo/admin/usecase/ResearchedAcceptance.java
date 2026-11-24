package ai.searchavo.admin.usecase;

import ai.searchavo.research.CheckResearch;
import ai.searchavo.research.ResearchAnswer;
import ai.searchavo.research.ResearchResult;
import ai.searchavo.research.ResearchRun;
import ai.searchavo.research.ResearchSubject;
import ai.searchavo.scout.usecase.CandidateEdit;
import ai.searchavo.shared.ResourceType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class ResearchedAcceptance {

  private ResearchedAcceptance() {}

  public static boolean acceptable(@Nullable ResearchRun run) {
    if (run == null || run.status() != ResearchRun.Status.DONE || run.result() == null) {
      return false;
    }
    ResearchResult result = run.result();
    return result.verdict() == ResearchAnswer.Verdict.KEEP
        && result.attributes().stream()
            .noneMatch(a -> a.status() == ResearchResult.Status.CONFLICT);
  }

  public static boolean describes(ResearchResult result, ResearchSubject now) {
    return result.attributes().stream()
        .allMatch(a -> Objects.equals(CheckResearch.currentValue(now, a.name()), a.current()));
  }

  public static Optional<CandidateEdit> edit(ResearchResult result) {
    Map<String, String> values =
        result.attributes().stream()
            .filter(ResearchedAcceptance::confirmedChange)
            .collect(
                Collectors.toMap(
                    ResearchResult.Attribute::name,
                    a -> a.proposed().getFirst().trim(),
                    (a, b) -> a));
    if (values.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new CandidateEdit(
            values.get("title"),
            parsed(values.get("type"), ResearchedAcceptance::type),
            parsed(values.get("tags"), ResearchedAcceptance::tags),
            values.get("canonicalUrl"),
            values.get("pricing"),
            parsed(values.get("openSource"), ResearchedAcceptance::yesOrNo),
            values.get("license"),
            values.get("audience"),
            values.get("techLevel"),
            values.get("description"),
            values.containsKey("description") ? Boolean.TRUE : null,
            values.get("language"),
            parsed(values.get("publishedAt"), ResearchedAcceptance::day)));
  }

  private static boolean confirmedChange(ResearchResult.Attribute a) {
    if (a.status() != ResearchResult.Status.CONFIRMED || a.proposed().size() != 1) {
      return false;
    }
    String proposed = a.proposed().getFirst().trim();
    return !proposed.isEmpty() && !proposed.equals(a.current() == null ? "" : a.current());
  }

  private static <T> @Nullable T parsed(@Nullable String value, Function<String, T> parse) {
    return value == null ? null : parse.apply(value);
  }

  private static ResourceType type(String value) {
    return ResourceType.valueOf(value);
  }

  private static Set<String> tags(String value) {
    List<String> tags =
        Arrays.stream(value.split(","))
            .map(t -> t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-"))
            .filter(t -> !t.isEmpty())
            .toList();
    return new LinkedHashSet<>(tags);
  }

  private static Boolean yesOrNo(String value) {
    return switch (value) {
      case "true" -> Boolean.TRUE;
      case "false" -> Boolean.FALSE;
      default -> throw new IllegalArgumentException("open source must be true or false");
    };
  }

  private static LocalDate day(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("not a date: " + value, e);
    }
  }
}
