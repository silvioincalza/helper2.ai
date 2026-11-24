package ai.searchavo.research.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record ResearchLabels(List<Label> labels) {

  static final Path FILE = Path.of("../eval/research-labels.tsv");

  static final Path MANUAL = Path.of("../eval/research-labels-manual.tsv");

  static final List<String> ATTRIBUTES =
      List.of("type", "canonicalUrl", "title", "license", "pricing", "openSource");

  private static final List<String> COLUMNS =
      List.of(
          "id",
          "item_url",
          "kind",
          "title_found",
          "type_found",
          "decision",
          "expected_type",
          "expected_canonical_url",
          "expected_title",
          "expected_license",
          "expected_pricing",
          "expected_open_source");

  record Label(
      String id,
      String url,
      String kind,
      String titleFound,
      String typeFound,
      String decision,
      Map<String, String> expected) {

    UUID itemId() {
      return UUID.nameUUIDFromBytes(("research-eval:" + id).getBytes(StandardCharsets.UTF_8));
    }

    boolean decided() {
      return decision.equals("KEEP") || decision.equals("REJECT") || decision.equals("DUPLICATE");
    }
  }

  static ResearchLabels readAll() {
    List<Label> all = new ArrayList<>(read(FILE).labels());
    if (Files.exists(MANUAL)) {
      all.addAll(read(MANUAL).labels());
    }
    return new ResearchLabels(List.copyOf(all));
  }

  static ResearchLabels read(Path file) {
    try {
      return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ResearchLabels parse(List<String> lines) {
    if (lines.isEmpty() || !List.of(lines.getFirst().split("\t", -1)).equals(COLUMNS)) {
      throw new IllegalArgumentException("the header must be: " + String.join(" ", COLUMNS));
    }
    List<Label> out = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      String[] c = line.split("\t", -1);
      if (c.length != COLUMNS.size()) {
        throw new IllegalArgumentException("a row needs " + COLUMNS.size() + " cells: " + line);
      }
      Map<String, String> expected = new LinkedHashMap<>();
      for (int i = 0; i < ATTRIBUTES.size(); i++) {
        String v = c[6 + i].strip();
        if (!v.isEmpty()) {
          expected.put(ATTRIBUTES.get(i), v);
        }
      }
      Label label = new Label(c[0], c[1], c[2], c[3], c[4], c[5].strip(), Map.copyOf(expected));
      if (label.decided()) {
        out.add(label);
      }
    }
    return new ResearchLabels(List.copyOf(out));
  }
}
