package ai.searchavo.api;

import ai.searchavo.api.common.model.AiContentMarkerDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

public final class AiContentMarking {

  public static final String POLICY = "/mission";

  private AiContentMarking() {}

  public static @Nullable AiContentMarkerDto marker(
      String provider, Collection<@Nullable String> models, List<String> fields) {
    if (fields.isEmpty()) {
      return null;
    }
    List<String> known = models.stream().filter(Objects::nonNull).distinct().toList();
    return new AiContentMarkerDto(true, provider, known, List.copyOf(fields), POLICY);
  }

  public static List<String> inEachItem(String array, List<? extends Collection<String>> heldBy) {
    Set<String> fields = new LinkedHashSet<>();
    heldBy.forEach(fields::addAll);
    List<String> paths = new ArrayList<>();
    for (String field : fields) {
      List<Integer> holders =
          IntStream.range(0, heldBy.size())
              .filter(i -> heldBy.get(i).contains(field))
              .boxed()
              .toList();
      if (holders.size() == heldBy.size()) {
        paths.add(array + "[]." + field);
      } else {
        holders.forEach(i -> paths.add(array + "[" + i + "]." + field));
      }
    }
    return paths;
  }
}
