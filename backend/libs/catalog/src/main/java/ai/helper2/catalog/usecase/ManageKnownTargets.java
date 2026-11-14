package ai.helper2.catalog.usecase;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetList;
import ai.helper2.catalog.KnownTargetList.Entry;
import ai.helper2.platform.AuditTrail;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ManageKnownTargets {

  public static final int MIN_NAME = 2;

  public static final int MAX_NAME = 60;

  public static final int MAX_OTHER_NAMES = 20;

  public static final int MAX_TARGETS = 1000;

  public sealed interface Saved {
    record Done(Entry entry, int released) implements Saved {}

    record Invalid() implements Saved {}

    record Taken(String name, String holder) implements Saved {}

    record Full() implements Saved {}

    record NotFound() implements Saved {}
  }

  public sealed interface Removed {
    record Done(Entry entry, int released) implements Removed {}

    record NotFound() implements Removed {}
  }

  private final KnownTargetList targets;
  private final AuditTrail audit;
  private final Clock clock;

  public ManageKnownTargets(KnownTargetList targets, AuditTrail audit, Clock clock) {
    this.targets = targets;
    this.audit = audit;
    this.clock = clock;
  }

  public List<Entry> list() {
    return targets.all();
  }

  public Saved add(
      String by, KnownTarget.Kind kind, @Nullable String name, @Nullable List<String> otherNames) {
    return add(by, kind, name, otherNames, KnownTarget.Origin.CURATED);
  }

  public Saved addDiscovered(
      String by, KnownTarget.Kind kind, @Nullable String name, @Nullable List<String> otherNames) {
    targets.lockForChange();
    Map<String, String> names = names(name, otherNames);
    Entry holder = names == null ? null : holderOf(names.keySet().iterator().next());
    if (names == null || holder == null) {
      return add(by, kind, name, otherNames, KnownTarget.Origin.DISCOVERED);
    }
    if (holder.kind() != kind) {
      audit.record(by, AuditTrail.Action.EDIT_KNOWN_TARGET, holder.id().toString(), "TAKEN");
      return new Saved.Taken(names.values().iterator().next(), holder.name());
    }
    List<String> others = new ArrayList<>(holder.otherNames());
    Set<String> held = keys(holder);
    names.forEach((key, shown) -> {
      if (held.add(key)) {
        others.add(shown);
      }
    });
    return change(by, holder.id(), holder.kind(), holder.name(), others);
  }

  private @Nullable Entry holderOf(String key) {
    UUID id = targets.holders(List.of(key)).get(key);
    return id == null ? null : found(id);
  }

  private Saved add(
      String by,
      KnownTarget.Kind kind,
      @Nullable String name,
      @Nullable List<String> otherNames,
      KnownTarget.Origin origin) {
    targets.lockForChange();
    UUID id = UUID.randomUUID();
    Saved saved = tryAdd(id, by, kind, name, otherNames, origin);
    audit.record(
        by,
        AuditTrail.Action.ADD_KNOWN_TARGET,
        id.toString(),
        saved instanceof Saved.Done ? added(origin) : outcome(saved),
        new AuditTrail.Change(
            null, saved instanceof Saved.Done d ? described(d.entry()) : null, null));
    return saved;
  }

  private Saved tryAdd(
      UUID id,
      String by,
      KnownTarget.Kind kind,
      @Nullable String name,
      @Nullable List<String> otherNames,
      KnownTarget.Origin origin) {
    Map<String, String> names = names(name, otherNames);
    if (names == null) {
      return new Saved.Invalid();
    }
    Saved.Taken taken = taken(names, null);
    if (taken != null) {
      return taken;
    }
    if (targets.all().size() >= MAX_TARGETS) {
      return new Saved.Full();
    }
    targets.add(id, kind, List.copyOf(names.values()), by, clock.instant(), origin);
    return new Saved.Done(found(id), 0);
  }

  public Saved change(
      String by,
      UUID id,
      KnownTarget.Kind kind,
      @Nullable String name,
      @Nullable List<String> otherNames) {
    targets.lockForChange();
    Entry before = targets.find(id);
    Saved saved = before == null ? new Saved.NotFound() : tryChange(before, kind, name, otherNames);
    audit.record(
        by,
        AuditTrail.Action.EDIT_KNOWN_TARGET,
        id.toString(),
        saved instanceof Saved.Done d ? "EDITED RELEASED " + d.released() : outcome(saved),
        new AuditTrail.Change(
            before == null ? null : described(before),
            saved instanceof Saved.Done d ? described(d.entry()) : null,
            null));
    return saved;
  }

  private Saved tryChange(
      Entry before,
      KnownTarget.Kind kind,
      @Nullable String name,
      @Nullable List<String> otherNames) {
    Map<String, String> names = names(name, otherNames);
    if (names == null) {
      return new Saved.Invalid();
    }
    Saved.Taken taken = taken(names, before.id());
    if (taken != null) {
      return taken;
    }
    Set<String> dropped = keys(before);
    dropped.removeAll(names.keySet());
    Set<String> kept = keys(before);
    kept.retainAll(names.keySet());
    int released = targets.release(dropped, KnownTarget.noLonger(before.kind()));
    if (kind != before.kind()) {
      targets.setKind(kept, kind);
    }
    targets.replace(before.id(), kind, List.copyOf(names.values()));
    return new Saved.Done(found(before.id()), released);
  }

  public Removed remove(String by, UUID id) {
    targets.lockForChange();
    Entry before = targets.find(id);
    if (before == null) {
      audit.record(by, AuditTrail.Action.REMOVE_KNOWN_TARGET, id.toString(), "NOT_FOUND");
      return new Removed.NotFound();
    }
    int released = targets.release(keys(before), KnownTarget.noLonger(before.kind()));
    targets.remove(id);
    audit.record(
        by,
        AuditTrail.Action.REMOVE_KNOWN_TARGET,
        id.toString(),
        "REMOVED RELEASED " + released,
        new AuditTrail.Change(described(before), null, null));
    return new Removed.Done(before, released);
  }

  private Entry found(UUID id) {
    Entry entry = targets.find(id);
    if (entry == null) {
      throw new IllegalStateException("known target " + id + " is gone");
    }
    return entry;
  }

  private Saved.@Nullable Taken taken(Map<String, String> names, @Nullable UUID self) {
    for (Map.Entry<String, UUID> held : targets.holders(names.keySet()).entrySet()) {
      if (!held.getValue().equals(self)) {
        return new Saved.Taken(names.get(held.getKey()), found(held.getValue()).name());
      }
    }
    return null;
  }

  static @Nullable Map<String, String> names(
      @Nullable String name, @Nullable List<String> otherNames) {
    List<String> all = new ArrayList<>();
    all.add(name == null ? "" : name);
    if (otherNames != null) {
      all.addAll(otherNames);
    }
    if (all.size() > 1 + MAX_OTHER_NAMES) {
      return null;
    }
    Map<String, String> byKey = new LinkedHashMap<>();
    for (String raw : all) {
      String one = raw == null ? "" : raw.strip();
      String key = KnownTarget.key(one);
      if (one.length() < MIN_NAME
          || one.length() > MAX_NAME
          || key.length() < MIN_NAME
          || one.codePoints().anyMatch(ManageKnownTargets::hidden)) {
        return null;
      }
      byKey.putIfAbsent(key, one);
    }
    return byKey;
  }

  private static boolean hidden(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.CONTROL || type == Character.FORMAT;
  }

  private static Set<String> keys(Entry entry) {
    Set<String> keys = new LinkedHashSet<>();
    entry.names().forEach(n -> keys.add(KnownTarget.key(n)));
    return keys;
  }

  private static String added(KnownTarget.Origin origin) {
    return origin == KnownTarget.Origin.DISCOVERED ? "ADDED DISCOVERED" : "ADDED";
  }

  private static String described(Entry entry) {
    return entry.kind().name() + " " + entry.name();
  }

  private static String outcome(Saved saved) {
    return switch (saved) {
      case Saved.Done d -> "DONE";
      case Saved.Invalid i -> "INVALID";
      case Saved.Taken t -> "TAKEN";
      case Saved.Full f -> "FULL";
      case Saved.NotFound n -> "NOT_FOUND";
    };
  }
}
