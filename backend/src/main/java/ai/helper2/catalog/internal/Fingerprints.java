package ai.helper2.catalog.internal;

import ai.helper2.search.SourceHit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeSet;

/** Content fingerprint of a source record, for change detection. Ignores retrieval time and rank. */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(SourceHit h) {
        String material = String.join("\u001f",
            h.getTitle(),
            Objects.toString(h.getDescription(), ""),
            h.getType().name(),
            Objects.toString(h.getLicense(), ""),
            Objects.toString(h.getPublished().getValue(), ""),
            Objects.toString(h.getUpdated().getValue(), ""),
            String.join(",", new TreeSet<>(h.getTags())),
            Objects.toString(h.getRepositoryUrl(), ""))
            + (h.getEventStart().getValue() == null ? "" : "|" + h.getEventStart().getValue() + "|" + h.getEventEnd().getValue() + "|" + h.getLocation());
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
