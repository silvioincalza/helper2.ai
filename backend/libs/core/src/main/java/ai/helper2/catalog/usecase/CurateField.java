package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CuratedFields;
import ai.helper2.platform.AuditTrail;
import ai.helper2.shared.Audience;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.TechLevel;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public class CurateField {

  private static final int MAX_AUDIT_VALUE = AuditTrail.Change.MAX_LENGTH;

  private static final Pattern SPDX = Pattern.compile("^[A-Za-z0-9.+-]{2,40}$");

  public static final int MAX_DESCRIPTION = 4000;

  private static final java.util.Set<String> LANGUAGES =
      java.util.Set.of(
          "simple", "ar", "hy", "eu", "ca", "da", "nl", "en", "fi", "fr", "de", "el", "hi", "hu",
          "id", "ga", "it", "lt", "no", "pt", "ro", "ru", "sr", "es", "sv", "ta", "tr");

  private record Rule(String column, Predicate<String> allowed) {}

  private static final Map<String, Rule> FIELDS =
      Map.of(
          "type",
          new Rule("type", v -> isOneOf(ResourceType.class, v) && !"OTHER".equals(v)),
          "pricing",
          new Rule("pricing", v -> isOneOf(Pricing.class, v)),
          "license",
          new Rule("license", v -> SPDX.matcher(v).matches()),
          "openSource",
          new Rule("open_source", v -> "true".equals(v) || "false".equals(v)),
          "techLevel",
          new Rule("tech_level", v -> isOneOf(TechLevel.class, v)),
          "audience",
          new Rule("audience", v -> isOneOf(Audience.class, v) && !"UNKNOWN".equals(v)),
          "language",
          new Rule("language", LANGUAGES::contains));

  private static final java.util.Set<String> AI_FIELDS =
      java.util.Set.of(
          "type",
          "pricing",
          "license",
          "openSource",
          "techLevel",
          "audience",
          "useCase",
          "platforms");

  public sealed interface Outcome {
    record Curated() implements Outcome {}

    sealed interface ValueRefused extends Outcome {}

    record UnknownField(String field) implements ValueRefused {}

    record ValueNotAllowed(String field) implements ValueRefused {}

    record InvalidDescription(int maxLength) implements ValueRefused {}

    record NotADate() implements ValueRefused {}

    record DateInTheFuture() implements ValueRefused {}

    record NoSuchResource() implements Outcome {}
  }

  public sealed interface DropOutcome {
    record Dropped(int values) implements DropOutcome {}

    record NotWrittenByAi(String field) implements DropOutcome {}

    record NoSuchResource() implements DropOutcome {}
  }

  private final CuratedFields fields;
  private final AuditTrail audit;
  private final Clock clock;

  public CurateField(CuratedFields fields, AuditTrail audit, Clock clock) {
    this.fields = fields;
    this.audit = audit;
    this.clock = clock;
  }

  public Outcome set(UUID resourceId, String field, String value, String by) {
    if ("description".equals(field)) {
      return setDescription(resourceId, value, false, by);
    }
    if ("publishedAt".equals(field)) {
      return setPublishedOn(resourceId, value, by);
    }
    Rule rule = FIELDS.get(field);
    if (rule == null) {
      return new Outcome.UnknownField(field);
    }
    String v = value == null ? "" : value.strip();
    if (!rule.allowed().test(v)) {
      return new Outcome.ValueNotAllowed(field);
    }
    String before = before(resourceId, field);
    if (!fields.set(resourceId, field, rule.column(), "open_source".equals(rule.column()), v)) {
      return new Outcome.NoSuchResource();
    }
    fields.rejectAiValues(resourceId, field);
    record(resourceId, "SET " + field, before, v, by);
    return new Outcome.Curated();
  }

  public Outcome setDescription(
      UUID resourceId, String description, boolean writtenByAi, String by) {
    String d = description == null ? "" : description.strip();
    if (d.isEmpty() || d.length() > MAX_DESCRIPTION) {
      return new Outcome.InvalidDescription(MAX_DESCRIPTION);
    }
    String origin = writtenByAi ? "AI_SUMMARY" : "CURATED";
    String before = before(resourceId, "description");
    if (!fields.setDescription(resourceId, d, origin)) {
      return new Outcome.NoSuchResource();
    }
    record(resourceId, "SET description " + origin, before, d, by);
    return new Outcome.Curated();
  }

  public Outcome setPublished(UUID resourceId, LocalDate publishedAt, String by) {
    String before = before(resourceId, "publishedAt");
    if (!fields.setPublished(resourceId, publishedAt)) {
      return new Outcome.NoSuchResource();
    }
    record(resourceId, "SET publishedAt", before, publishedAt.toString(), by);
    return new Outcome.Curated();
  }

  private Outcome setPublishedOn(UUID resourceId, String value, String by) {
    LocalDate date;
    try {
      date = LocalDate.parse(value == null ? "" : value.strip());
    } catch (DateTimeParseException e) {
      return new Outcome.NotADate();
    }
    if (date.isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(1))) {
      return new Outcome.DateInTheFuture();
    }
    return setPublished(resourceId, date, by);
  }

  private @Nullable String before(UUID resourceId, String field) {
    return fields
        .current(resourceId, field)
        .map(c -> auditValue(c.value() == null ? "(none)" : c.value(), c.curated()))
        .orElse(null);
  }

  private void record(
      UUID resourceId, String outcome, @Nullable String before, String after, String by) {
    audit.record(
        by,
        AuditTrail.Action.SET_FIELD,
        resourceId.toString(),
        outcome,
        new AuditTrail.Change(before, auditValue(after, false), null));
  }

  static String auditValue(String value, boolean curated) {
    String suffix = curated ? " (curated)" : "";
    int room = MAX_AUDIT_VALUE - suffix.length();
    String v = value.replaceAll("\\s+", " ").strip();
    String cut = v.length() <= room ? v : v.substring(0, room - 1) + "…";
    return cut + suffix;
  }

  public DropOutcome drop(UUID resourceId, String field, String by) {
    if (!AI_FIELDS.contains(field)) {
      return new DropOutcome.NotWrittenByAi(field);
    }
    if (!fields.exists(resourceId)) {
      return new DropOutcome.NoSuchResource();
    }
    int dropped = fields.rejectAiValues(resourceId, field);
    audit.record(
        by,
        AuditTrail.Action.DROP_FIELD,
        resourceId.toString(),
        "DROPPED " + field + " " + dropped);
    return new DropOutcome.Dropped(dropped);
  }

  private static <E extends Enum<E>> boolean isOneOf(Class<E> type, String v) {
    return Arrays.stream(type.getEnumConstants()).anyMatch(e -> e.name().equals(v));
  }
}
