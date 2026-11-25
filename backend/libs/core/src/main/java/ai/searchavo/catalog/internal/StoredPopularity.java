package ai.searchavo.catalog.internal;

/**
 * The counts of a source record ({@code extra.popularity}) have two writers: the writer of hits and
 * the repository sweep. Both store through this one rule, so neither removes what the other read.
 */
final class StoredPopularity {

  private static final String ENTRIES =
      """
      (SELECT DISTINCT ON (e ->> 'kind') e ->> 'kind' AS kind, e AS entry,
              CASE WHEN pg_input_is_valid(e ->> 'asOf', 'timestamptz')
                   THEN CAST(e ->> 'asOf' AS timestamptz) END AS read_at
       FROM jsonb_array_elements(CASE WHEN jsonb_typeof(%1$s) = 'array' THEN %1$s ELSE '[]'::jsonb END) e
       ORDER BY e ->> 'kind',
                CASE WHEN pg_input_is_valid(e ->> 'asOf', 'timestamptz')
                     THEN CAST(e ->> 'asOf' AS timestamptz) END DESC NULLS LAST)\
      """;

  // ADR-0020 §7, newest wins per kind of count: a count that is not stated keeps its stored
  // value; a stated count takes the place of the stored one unless it was read before it. An
  // entry without a date it can read counts as older than a dated one; on equal dates the stated
  // count wins.
  private static final String MERGED =
      """
      (SELECT coalesce(jsonb_agg(CASE WHEN stated.entry IS NULL THEN kept.entry
                                      WHEN kept.read_at IS NOT NULL
                                           AND (stated.read_at IS NULL OR stated.read_at < kept.read_at)
                                        THEN kept.entry
                                      ELSE stated.entry
                                 END ORDER BY coalesce(stated.kind, kept.kind)), '[]'::jsonb)
       FROM %s kept
       FULL JOIN %s stated ON stated.kind = kept.kind)\
      """;

  private StoredPopularity() {}

  /**
   * The SQL expression of the counts to store, as a JSON array.
   *
   * @param kept the SQL expression of the array the record holds
   * @param stated the SQL expression of the array a writer read
   */
  static String merged(String kept, String stated) {
    return MERGED.formatted(ENTRIES.formatted(kept), ENTRIES.formatted(stated));
  }
}
