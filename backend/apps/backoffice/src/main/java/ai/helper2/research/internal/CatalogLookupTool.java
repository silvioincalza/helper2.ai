package ai.helper2.research.internal;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargets;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.research.ResearchTool;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CatalogLookupTool implements ResearchTool {

  private final ReviewQueue queue;
  private final KnownTargets targets;

  CatalogLookupTool(ReviewQueue queue, KnownTargets targets) {
    this.queue = queue;
    this.targets = targets;
  }

  @Override
  public String name() {
    return "catalog_lookup";
  }

  @Override
  public String description() {
    return "Look for the item in helper2.ai's own catalog: a resource listed at a URL or at its site's root, and a"
        + " known vendor, service or tool by name. Finds duplicates. Not evidence.";
  }

  @Override
  public String parameters() {
    return """
    {"type":"object","properties":{"url":{"type":"string"},"name":{"type":"string"}}}\
    """;
  }

  @Override
  public Output call(String arguments) {
    String url = ToolArgs.text(arguments, "url");
    String name = ToolArgs.text(arguments, "name");
    if (url == null && name == null) {
      return Output.error("give a url or a name");
    }
    List<String> found = new ArrayList<>();
    for (String candidate : urls(url)) {
      Optional<ReviewQueue.Listed> listed = queue.listedAs(candidate);
      listed.ifPresent(l -> found.add("resource: " + l.title() + " · " + candidate));
    }
    if (name != null) {
      Map<String, KnownTarget> named = targets.named(List.of(name));
      named.values().forEach(t -> found.add("known target: " + t.kind().name() + " · " + t.name()));
    }
    if (found.isEmpty()) {
      return new Output("nothing in the catalog matches", null);
    }
    return new Output(String.join("\n", found), null);
  }

  private static Set<String> urls(String url) {
    Set<String> out = new LinkedHashSet<>();
    if (url == null) {
      return out;
    }
    out.add(url.strip());
    try {
      String host = URI.create(url.strip()).getHost();
      if (host != null) {
        String h = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        out.add("https://" + h);
        out.add("https://www." + h);
      }
    } catch (IllegalArgumentException e) {
      return out;
    }
    return out;
  }
}
