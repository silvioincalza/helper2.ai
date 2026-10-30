package ai.helper2.sources.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an "awesome" README as a link graph: the GitHub repositories linked from list items and table
 * rows, with the heading they sit under. The list's own prose is not kept; facts about each repository
 * come from the GitHub API, so Helper2 does not copy the list author's text.
 */
final class AwesomeListParser {

    record Entry(String fullName, String section) {
    }

    /** Owner and name follow GitHub's allowed characters, so they are safe to embed in a GraphQL string. */
    private static final Pattern REPO_LINK = Pattern.compile("https?://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+|]|\\d+\\.)\\s");
    /** First path segments that are GitHub pages, not users or organizations. */
    private static final Set<String> NOT_OWNERS = Set.of("topics", "orgs", "sponsors", "features", "marketplace", "apps",
        "collections", "login", "settings", "about", "site", "trending", "search", "users", "readme", "contact",
        "pricing", "enterprise", "github", "security", "explore", "notifications", "new", "codespaces");
    private static final int MAX_SECTION = 80;

    private AwesomeListParser() {
    }

    /** Unique repositories in README order; the list itself and GitHub's own pages are left out. */
    static List<Entry> entries(String markdown, String listFullName) {
        Map<String, Entry> unique = new LinkedHashMap<>();
        String section = "";
        String self = listFullName.toLowerCase(Locale.ROOT);
        for (String line : markdown.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                section = clip(trimmed.replaceFirst("^#+", "").strip());
                continue;
            }
            if (!LIST_ITEM.matcher(line).find()) {
                continue;
            }
            Matcher m = REPO_LINK.matcher(line);
            while (m.find()) {
                String owner = m.group(1);
                String name = cleanName(m.group(2));
                if (name.isEmpty() || NOT_OWNERS.contains(owner.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String fullName = owner + "/" + name;
                String key = fullName.toLowerCase(Locale.ROOT);
                if (!key.equals(self)) {
                    unique.putIfAbsent(key, new Entry(fullName, section));
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String cleanName(String raw) {
        String name = raw.endsWith(".git") ? raw.substring(0, raw.length() - 4) : raw;
        // A sentence-ending dot or ".)" sticks to the match.
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static String clip(String s) {
        return s.length() <= MAX_SECTION ? s : s.substring(0, MAX_SECTION);
    }
}
