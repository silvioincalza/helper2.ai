package ai.helper2.sources.support;

import java.time.Duration;

/** Per-source configuration. Tokens are server-side only and never logged. */
public record SourceSettings(boolean enabled, String baseUrl, String token, Duration timeout, Duration cacheTtl) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    @Override
    public String toString() {
        return "SourceSettings[enabled=" + enabled + ", baseUrl=" + baseUrl + ", token=" + (hasToken() ? "***" : "none")
            + ", timeout=" + timeout + ", cacheTtl=" + cacheTtl + "]";
    }
}
