package ai.helper2.catalog.internal;

final class TargetNames {

  static final String[] TYPES_BY_TITLE = {
    "PRODUCT", "MODEL", "LIBRARY", "MCP_SERVER", "AGENT", "SKILL", "DATASET"
  };

  static final String[] TYPES_BY_REPOSITORY_NAME = {
    "PRODUCT", "MODEL", "LIBRARY", "AGENT", "DATASET"
  };

  private static final String WORDS = "'[-_. ]+', ' ', 'g'";

  static final String NAMED =
      """
      SELECT w.name AS wanted, r.id
      FROM wanted w JOIN resource r ON lower(r.title) = w.name
      WHERE r.data_origin IN ('INDEXED', 'CURATED') AND r.type = ANY(:byTitle)
        AND (r.type <> 'MCP_SERVER' OR w.name ~ '(^|[^a-z0-9])mcp([^a-z0-9]|$)')
      UNION
      SELECT w.name, r.id
      FROM wanted w JOIN resource r
        ON regexp_replace(substring(lower(r.title) FROM '[^/]*$'), %s) = regexp_replace(w.name, %s)
      WHERE r.data_origin IN ('INDEXED', 'CURATED') AND r.type = ANY(:byRepositoryName)
        AND position('/' IN r.title) > 0
      """
          .formatted(WORDS, WORDS);

  private TargetNames() {}
}
