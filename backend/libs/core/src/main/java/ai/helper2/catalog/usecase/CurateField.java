package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CuratedFields;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
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

public class CurateField {

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

  private final CuratedFields fields;
  private final AuditTrail audit;
  private final Clock clock;

  public CurateField(CuratedFields fields, AuditTrail audit, Clock clock) {
    this.fields = fields;
    this.audit = audit;
    this.clock = clock;
  }

  public void set(UUID resourceId, String field, String value, String by) {
    if ("description".equals(field)) {
      setDescription(resourceId, value, false, by);
      return;
    }
    if ("publishedAt".equals(field)) {
      setPublished(resourceId, publishedDate(value), by);
      return;
    }
    Rule rule = FIELDS.get(field);
    if (rule == null) {
      throw new BadRequestException("This field cannot be set: " + field + ".");
    }
    String v = value == null ? "" : value.strip();
    if (!rule.allowed().test(v)) {
      throw new BadRequestException("This value is not allowed for " + field + ".");
    }
    if (!fields.set(resourceId, field, rule.column(), "open_source".equals(rule.column()), v)) {
      throw new NotFoundException("Resource not found.");
    }
    fields.rejectAiValues(resourceId, field);
    audit.record(by, AuditTrail.Action.SET_FIELD, resourceId.toString(), "SET " + field);
  }

  public void setDescription(UUID resourceId, String description, boolean writtenByAi, String by) {
    String d = description == null ? "" : description.strip();
    if (d.isEmpty() || d.length() > MAX_DESCRIPTION) {
      throw new BadRequestException(
          "The description must be 1 to " + MAX_DESCRIPTION + " characters.");
    }
    String origin = writtenByAi ? "AI_SUMMARY" : "CURATED";
    if (!fields.setDescription(resourceId, d, origin)) {
      throw new NotFoundException("Resource not found.");
    }
    audit.record(
        by, AuditTrail.Action.SET_FIELD, resourceId.toString(), "SET description " + origin);
  }

  public void setPublished(UUID resourceId, LocalDate publishedAt, String by) {
    if (!fields.setPublished(resourceId, publishedAt)) {
      throw new NotFoundException("Resource not found.");
    }
    audit.record(by, AuditTrail.Action.SET_FIELD, resourceId.toString(), "SET publishedAt");
  }

  public int drop(UUID resourceId, String field, String by) {
    if (!AI_FIELDS.contains(field)) {
      throw new BadRequestException("The AI writes no such field.");
    }
    if (!fields.exists(resourceId)) {
      throw new NotFoundException("Resource not found.");
    }
    int dropped = fields.rejectAiValues(resourceId, field);
    audit.record(
        by,
        AuditTrail.Action.DROP_FIELD,
        resourceId.toString(),
        "DROPPED " + field + " " + dropped);
    return dropped;
  }

  private LocalDate publishedDate(String value) {
    LocalDate date;
    try {
      date = LocalDate.parse(value == null ? "" : value.strip());
    } catch (DateTimeParseException e) {
      throw new BadRequestException("The published date must be YYYY-MM-DD.");
    }
    if (date.isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(1))) {
      throw new BadRequestException("The published date is in the future.");
    }
    return date;
  }

  private static <E extends Enum<E>> boolean isOneOf(Class<E> type, String v) {
    return Arrays.stream(type.getEnumConstants()).anyMatch(e -> e.name().equals(v));
  }
}
