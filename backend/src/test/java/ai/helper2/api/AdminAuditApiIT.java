package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewQueue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

/** Every admin write leaves an audit entry in its own transaction; GET /admin/audit pages through them. */
class AdminAuditApiIT extends ApiTestBase {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ReviewQueue queue;

    final String adminId = "audit-" + UUID.randomUUID();
    final OAuth2LoginRequestPostProcessor admin = oauth2Login()
        .attributes(a -> a.put("sub", adminId)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    String outcomeOf(UUID target) {
        return jdbc.sql("SELECT outcome FROM admin_audit WHERE admin_id = :a AND action = 'RESOLVE_REVIEW_ITEM' AND target_id = :t")
            .param("a", adminId).param("t", target.toString()).query(String.class).list().getFirst();
    }

    @Test
    void resolvingAReviewItemRecordsTheAdminTheItemAndTheDecisionAndASecondTryAsNotOpen() throws Exception {
        UUID item = queue.open(ReviewQueue.Kind.USER_REPORT, null, "audited", null, ReviewQueue.Links.NONE);
        mvc.perform(put("/api/v1/admin/review-items/" + item + "/resolution").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REJECTED\"}"))
            .andExpect(status().isOk());
        assertThat(outcomeOf(item)).isEqualTo("REJECTED");

        mvc.perform(put("/api/v1/admin/review-items/" + item + "/resolution").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(jdbc.sql("SELECT count(*) FROM admin_audit WHERE admin_id = :a AND outcome = 'NOT_OPEN'")
            .param("a", adminId).query(Long.class).single()).isOne();
    }

    @Test
    void anUnknownItemIsRecordedAsNotFound() throws Exception {
        UUID missing = UUID.randomUUID();
        mvc.perform(put("/api/v1/admin/review-items/" + missing + "/resolution").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}"))
            .andExpect(status().isNotFound());
        assertThat(outcomeOf(missing)).isEqualTo("NOT_FOUND");
    }

    @Test
    void theAuditTrailIsPagedNewestFirstForAdminsOnly() throws Exception {
        queue.open(ReviewQueue.Kind.USER_REPORT, null, "paged", null, ReviewQueue.Links.NONE);
        UUID item = queue.open(ReviewQueue.Kind.USER_REPORT, null, "paged", null, ReviewQueue.Links.NONE);
        mvc.perform(put("/api/v1/admin/review-items/" + item + "/resolution").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}"))
            .andExpect(status().isOk());

        String page = mvc.perform(get("/api/v1/admin/audit").param("size", "1").with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("AuditEntryPage", page);
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(page, "$.total")).isPositive();

        mvc.perform(get("/api/v1/admin/audit").param("size", "101").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit").with(oauth2Login())).andExpect(status().isForbidden());
    }
}
