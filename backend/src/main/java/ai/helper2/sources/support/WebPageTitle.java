package ai.helper2.sources.support;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The title of a web page result, cleaned for display: the HTML {@code <title>} often ends with the site name, and a
 * GitHub repository page carries its whole description. Conservative: only a final segment that names the result's own
 * site goes, and the title is never empty.
 *
 * <ul>
 *   <li>Whitespace is trimmed and inner runs collapse to one space.</li>
 *   <li>On github.com, {@code GitHub - owner/repo: description · GitHub} becomes {@code owner/repo}; the description is
 *       returned apart.</li>
 *   <li>A final {@code " | Site"}, {@code " - Site"}, {@code " · Site"}, {@code " — Site"} or {@code " – Site"} goes when
 *       Site is the host ({@code scored.tools}), the registrable domain ({@code Kaily.ai} on kaily.ai) or its name
 *       without punctuation and spaces ({@code Medium} on medium.com, {@code Hugging Face} on huggingface.co). One
 *       segment at most; a leading site name ({@code Ollama - Get up and running}) stays.</li>
 * </ul>
 *
 * @param title       the cleaned title, never blank
 * @param description the repository description of a GitHub title; null otherwise
 */
public record WebPageTitle(String title, String description) {

    private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0\\u2007\\u202f]+");
    private static final List<String> SEPARATORS = List.of(" | ", " - ", " · ", " — ", " – ");
    private static final Pattern GITHUB_REPOSITORY = Pattern.compile("^GitHub - ([A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+)(?:: (.*))?$");
    /** Second-level labels under which the registrable name is one label further left (bbc.co.uk). */
    private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or");

    /** Cleans [raw] for a page on [host]; without a host only the whitespace is cleaned. */
    public static WebPageTitle of(String raw, String host) {
        String title = SPACES.matcher(raw == null ? "" : raw).replaceAll(" ").strip();
        if (host == null || host.isBlank()) {
            return new WebPageTitle(title, null);
        }
        String site = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        if (site.equals("github.com")) {
            WebPageTitle repository = repository(title);
            if (repository != null) {
                return repository;
            }
        }
        return new WebPageTitle(withoutSiteName(title, site), null);
    }

    private static WebPageTitle repository(String title) {
        Matcher m = GITHUB_REPOSITORY.matcher(title.endsWith(" · GitHub") ? title.substring(0, title.length() - " · GitHub".length()) : title);
        if (!m.matches()) {
            return null;
        }
        String description = m.group(2) == null || m.group(2).isBlank() ? null : m.group(2).strip();
        return new WebPageTitle(m.group(1), description);
    }

    private static String withoutSiteName(String title, String site) {
        int cut = -1;
        String separator = null;
        for (String s : SEPARATORS) {
            int at = title.lastIndexOf(s);
            if (at > cut) {
                cut = at;
                separator = s;
            }
        }
        if (cut <= 0) {
            return title;
        }
        String rest = title.substring(0, cut).strip();
        String segment = title.substring(cut + separator.length()).strip();
        return !rest.isEmpty() && namesSite(segment, site) ? rest : title;
    }

    private static boolean namesSite(String segment, String site) {
        String s = segment.toLowerCase(Locale.ROOT);
        String[] labels = site.split("\\.");
        if (labels.length < 2) {
            return s.equals(site);
        }
        int nameAt = labels.length >= 3 && SECOND_LEVEL.contains(labels[labels.length - 2]) ? labels.length - 3 : labels.length - 2;
        String registrable = String.join(".", List.of(labels).subList(nameAt, labels.length));
        String name = lettersAndDigits(labels[nameAt]);
        String compact = lettersAndDigits(s);
        return s.equals(site) || s.equals(registrable) || (!compact.isEmpty() && compact.equals(name));
    }

    private static String lettersAndDigits(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
