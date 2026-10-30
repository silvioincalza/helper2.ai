package ai.helper2.catalog;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/** GET /admin/review-items: paged, newest first, filtered by status (SUPERSEDED included). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReviewItemsApiIT {

    static final OAuth2LoginRequestPostProcessor ADMIN = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    final UUID resource = UUID.randomUUID();

    @BeforeEach
    void threeSupersededItemsOfOneResource() {
        String url = "https://review-items.example/" + resource;
        jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
                VALUES (:id, :url, :url, 'Paged', 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
                """).param("id", resource).param("url", url).update();
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                    INSERT INTO review_item (id, kind, status, resource_id, reason, created_at, resolved_at)
                    VALUES (:id, 'ENRICHMENT_VALIDATION', 'SUPERSEDED', :rid, :reason,
                            TIMESTAMPTZ '2000-01-01' + make_interval(days => :i), now())
                    """).param("id", UUID.randomUUID()).param("rid", resource).param("reason", "item " + i).param("i", i).update();
        }
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resource).update();
    }

    /** Other tests share the database: page through this resource's items only by their place among SUPERSEDED ones. */
    @Test
    void pagesThroughOneStatusNewestFirst() throws Exception {
        long others = jdbc.sql("SELECT count(*) FROM review_item WHERE status = 'SUPERSEDED' AND resource_id <> :id")
            .param("id", resource).query(Long.class).single();
        // Every other superseded item is newer than 2000, so these three are the last ones.
        int size = 2;
        int lastPage = (int) ((others + 2) / size);
        mvc.perform(get("/api/v1/admin/review-items").param("status", "SUPERSEDED")
                .param("page", String.valueOf(lastPage)).param("size", String.valueOf(size)).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(others + 3))
            .andExpect(jsonPath("$.items[-1].reason").value("item 0"))
            .andExpect(jsonPath("$.items[*].status").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("SUPERSEDED"))));
    }

    @Test
    void aPageHoldsAtMostTheRequestedSize() throws Exception {
        mvc.perform(get("/api/v1/admin/review-items").param("size", "1").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void refusesPagesLargerThanOneHundred() throws Exception {
        mvc.perform(get("/api/v1/admin/review-items").param("size", "101").with(ADMIN))
            .andExpect(status().isBadRequest());
    }
}
