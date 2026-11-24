package ai.searchavo.sources.pagestatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * UC-030 BR-014: what a site's robots.txt says to the Scout's status requests (RFC 9309): the group
 * that names searchavo.ai, else the group for every robot; the longest rule that matches the path
 * decides, and an allow wins a tie.
 */
final class RobotsRules {

  static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

  static final RobotsRules DISALLOW_ALL = new RobotsRules(List.of(new Rule("/", false)));

  static final int MAX_RULES = 1_000;

  static final int MAX_PATTERN = 500;

  /** The names a robots.txt can use for this robot: the product name of its User-Agent. */
  private static final Set<String> OWN_NAMES =
      Set.of("searchavo", "searchavo.ai", "searchavo.ai-metasearch");

  private record Rule(String pattern, boolean allow) {}

  private final List<Rule> rules;

  private RobotsRules(List<Rule> rules) {
    this.rules = rules;
  }

  static RobotsRules parse(String text) {
    List<Rule> own = new ArrayList<>();
    List<Rule> everyRobot = new ArrayList<>();
    boolean namesUs = false;
    boolean namesAll = false;
    boolean readingAgents = false;
    boolean ownGroupSeen = false;
    for (String raw : text.split("\\R")) {
      String line = stripComment(raw);
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).strip();
      if (key.equals("user-agent")) {
        if (!readingAgents) {
          namesUs = false;
          namesAll = false;
        }
        readingAgents = true;
        String agent = value.toLowerCase(Locale.ROOT);
        namesUs |= OWN_NAMES.contains(agent);
        namesAll |= agent.equals("*");
        ownGroupSeen |= namesUs;
      } else if (key.equals("allow") || key.equals("disallow")) {
        readingAgents = false;
        if (value.isEmpty() || value.length() > MAX_PATTERN) {
          continue;
        }
        Rule rule = new Rule(value, key.equals("allow"));
        if (namesUs && own.size() < MAX_RULES) {
          own.add(rule);
        }
        if (namesAll && everyRobot.size() < MAX_RULES) {
          everyRobot.add(rule);
        }
      }
    }
    return new RobotsRules(List.copyOf(ownGroupSeen ? own : everyRobot));
  }

  boolean allows(String pathAndQuery) {
    String path = pathAndQuery.isEmpty() ? "/" : pathAndQuery;
    int longest = -1;
    boolean allowed = true;
    for (Rule rule : rules) {
      int length = rule.pattern().length();
      if (matches(rule.pattern(), path)
          && (length > longest || length == longest && rule.allow())) {
        longest = length;
        allowed = rule.allow();
      }
    }
    return allowed;
  }

  /** A rule matches from the start of the path; {@code *} is any text, a last {@code $} the end. */
  static boolean matches(String pattern, String path) {
    boolean anchored = pattern.endsWith("$");
    String glob =
        (anchored ? pattern.substring(0, pattern.length() - 1) : pattern) + (anchored ? "" : "*");
    int p = 0;
    int s = 0;
    int star = -1;
    int resume = 0;
    while (s < path.length()) {
      if (p < glob.length() && glob.charAt(p) == '*') {
        star = p++;
        resume = s;
      } else if (p < glob.length() && glob.charAt(p) == path.charAt(s)) {
        p++;
        s++;
      } else if (star >= 0) {
        p = star + 1;
        s = ++resume;
      } else {
        return false;
      }
    }
    while (p < glob.length() && glob.charAt(p) == '*') {
      p++;
    }
    return p == glob.length();
  }

  private static String stripComment(String line) {
    int hash = line.indexOf('#');
    return hash < 0 ? line : line.substring(0, hash);
  }
}
