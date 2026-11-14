package ai.helper2.catalog.internal;

final class UrlKeys {

  private UrlKeys() {}

  static String keyOf(String urlExpression) {
    return "uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, " + urlExpression + ")";
  }
}
