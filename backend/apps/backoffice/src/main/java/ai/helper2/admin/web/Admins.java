package ai.helper2.admin.web;

import org.springframework.security.core.context.SecurityContextHolder;

final class Admins {

  private Admins() {}

  static String id() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
