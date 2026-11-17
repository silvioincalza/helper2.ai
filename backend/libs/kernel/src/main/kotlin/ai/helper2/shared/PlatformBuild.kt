package ai.helper2.shared

object PlatformBuild {

  private val OS =
    listOf(
      "linux",
      "darwin",
      "macos",
      "win32",
      "windows",
      "freebsd",
      "openbsd",
      "netbsd",
      "android",
      "sunos",
      "aix",
    )

  private val ARCH =
    listOf(
      "x64",
      "x86",
      "x86-64",
      "x86_64",
      "ia32",
      "arm",
      "arm64",
      "aarch64",
      "armv7",
      "riscv64",
      "ppc64",
      "ppc64le",
      "s390x",
      "loong64",
      "mips64el",
      "universal",
    )

  private val LIBC = listOf("musl", "gnu", "gnueabihf", "msvc", "eabi")

  private val SUFFIX =
    Regex(
      "(?:^|[-/])(?:${OS.joinToString("|")})-(?:${ARCH.joinToString("|")})" +
        "(?:-(?:${LIBC.joinToString("|")}))?$"
    )

  @JvmStatic
  fun isPlatformBuild(packageName: String): Boolean =
    SUFFIX.containsMatchIn(packageName.lowercase())
}
