package ai.helper2.platform;

/** pgvector text form of an embedding, for {@code CAST(:v AS vector)}: catalog vectors (ADR 0012) and guide questions (ADR 0041). */
public final class PgVectors {

    private PgVectors() {
    }

    /** {@code [0.1,-2.5,…]}; a value that is not finite is refused, as pgvector would. */
    public static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (!Float.isFinite(v[i])) {
                throw new IllegalArgumentException("embedding value is not finite");
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
