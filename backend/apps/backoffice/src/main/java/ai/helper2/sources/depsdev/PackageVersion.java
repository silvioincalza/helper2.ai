package ai.helper2.sources.depsdev;

public record PackageVersion(Ecosystem ecosystem, String name, String provenance) {

  public static final String UNVERIFIED = "UNVERIFIED_METADATA";

  public boolean verified() {
    return !UNVERIFIED.equals(provenance);
  }
}
