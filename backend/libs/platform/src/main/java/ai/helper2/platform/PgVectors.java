package ai.helper2.platform;

public final class PgVectors {

  private PgVectors() {}

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
