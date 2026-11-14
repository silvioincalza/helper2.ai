package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CategoryBackfillApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  @Test
  void anAdminStartsTheBackfillAndItIsAudited() throws Exception {
    String adminId = "categories-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/admin/category-backfill")
                .with(csrf().asHeader())
                .with(
                    oauth2Login()
                        .attributes(a -> a.put("sub", adminId))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isAccepted());

    assertThat(
            jdbc.sql(
                    "SELECT outcome FROM admin_audit WHERE admin_id = :a AND action = 'BACKFILL_CATEGORIES'")
                .param("a", adminId)
                .query(String.class)
                .list())
        .containsExactly("STARTED");
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (markers() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(markers()).isEqualTo(1);
  }

  private int markers() {
    return jdbc.sql("SELECT count(*) FROM job_marker WHERE name = 'categories'")
        .query(Integer.class)
        .single();
  }

  @Test
  void aGuestCannotStartTheBackfill() throws Exception {
    mvc.perform(post("/api/v1/admin/category-backfill").with(csrf().asHeader()))
        .andExpect(status().isUnauthorized());
  }
}
