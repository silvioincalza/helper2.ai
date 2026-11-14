package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CuratedFields;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.shared.Audience;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.TechLevel;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class CurateField {

  private static final Pattern SPDX = Pattern.compile("^[A-Za-z0-9.+-]{2,40}$");

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
          new Rule("audience", v -> isOneOf(Audience.class, v) && !"UNKNOWN".equals(v)));

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

  public CurateField(CuratedFields fields, AuditTrail audit) {
    this.fields = fields;
    this.audit = audit;
  }

  public void set(UUID resourceId, String field, String value, String by) {
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

  private static <E extends Enum<E>> boolean isOneOf(Class<E> type, String v) {
    return Arrays.stream(type.getEnumConstants()).anyMatch(e -> e.name().equals(v));
  }
}
