package ai.helper2.setup.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.setup.Code;
import ai.helper2.setup.GuideSection;
import ai.helper2.setup.GuideSectionKind;
import ai.helper2.setup.GuideStep;
import ai.helper2.setup.Material;
import ai.helper2.setup.MaterialKind;
import ai.helper2.setup.ProposedGuide;
import ai.helper2.setup.ProposedSection;
import ai.helper2.setup.ProposedStep;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.ToolGuideModel;
import ai.helper2.setup.ToolGuideStatus;
import ai.helper2.setup.ToolGuideTtls;
import ai.helper2.setup.ToolGuideUnavailable;
import ai.helper2.setup.ToolGuides;
import ai.helper2.setup.ToolRef;
import ai.helper2.testing.InMemorySharedCache;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** What a tool guide answer leaves in the shared cache: the checked steps and document links, never a document's text. */
class SharedToolGuideCacheTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);
    private static final String README_ONLY = "Unrelated paragraph that only the README holds.";
    private static final ToolGuideTtls TTLS = new ToolGuideTtls(Duration.ofDays(7), Duration.ofMinutes(10));
    private static final ToolRef REF = ToolRef.Companion.of("acme-x", "LIBRARY", List.of("https://github.com/acme/x"), List.of());

    private final InMemorySharedCache shared = new InMemorySharedCache("tool-guide");
    private final SharedToolGuideCache store = new SharedToolGuideCache(shared, JsonMapper.builder().build());

    @Test
    void storesTheAnswerWithoutTheDocumentText_andReadsItBack() {
        ToolGuides guides = new ToolGuides(ref -> List.of(readme()), new Model(), store, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();

        ToolGuideAnswer answered = guides.answer(resourceId, REF);

        assertThat(shared.values).hasSize(1).allSatisfy((key, raw) -> {
            assertThat(key).matches("[0-9a-f]{64}");
            assertThat(raw).contains("npm install acme-x").doesNotContain(README_ONLY);
        });
        assertThat(shared.ttls.values()).containsOnly(Duration.ofDays(7));
        ToolGuideAnswer kept = guides.answer(resourceId, REF);
        assertThat(kept).isEqualTo(answered);
        assertThat(kept.getSections()).containsExactly(new GuideSection(GuideSectionKind.INSTALL, List.of(
            new GuideStep("Install it.", "readme", "Install with:", new Code("bash", "npm install acme-x"), false))));
    }

    @Test
    void theNextAskIsServedFromTheSharedCacheWithoutAModelCall() {
        Model model = new Model();
        ToolGuides guides = new ToolGuides(ref -> List.of(readme()), model, store, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();

        ToolGuideAnswer first = guides.answer(resourceId, REF);
        ToolGuideAnswer second = guides.answer(resourceId, REF);

        assertThat(model.calls).isEqualTo(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void anUnreadableEntryCostsOneModelCall_thenTheRewrittenEntryServes() {
        Model model = new Model();
        ToolGuides guides = new ToolGuides(ref -> List.of(readme()), model, store, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();
        guides.answer(resourceId, REF);
        shared.values.replaceAll((key, raw) -> "{\"v\":2,\"answer\":{\"status\":\"NOT_A_STATUS\"}}");

        guides.answer(resourceId, REF);
        guides.answer(resourceId, REF);
        guides.answer(resourceId, REF);

        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    void anEntryOfAnotherVersionIsAMiss() {
        assertThat(store.decode("{\"v\":1,\"answer\":{\"status\":\"OK\"}}")).isNull();
    }

    @Test
    void anUnreadableModelAnswerIsKeptForTheShortTtl_andServesTheNextAsk() {
        Model model = new Model(new ToolGuideUnavailable(ToolGuideUnavailable.Reason.UNREADABLE));
        ToolGuides guides = new ToolGuides(ref -> List.of(readme()), model, store, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();

        ToolGuideAnswer first = guides.answer(resourceId, REF);
        ToolGuideAnswer second = guides.answer(resourceId, REF);

        assertThat(shared.ttls.values()).containsOnly(Duration.ofMinutes(10));
        assertThat(model.calls).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(second.getStatus()).isEqualTo(ToolGuideStatus.LLM_UNAVAILABLE);
        assertThat(second.getUnavailable()).isEqualTo("error");
    }

    @Test
    void anUnavailableStoreIsAMiss_andTheModelAnswers() {
        shared.down = true;
        Model model = new Model();
        ToolGuides guides = new ToolGuides(ref -> List.of(readme()), model, store, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();

        guides.answer(resourceId, REF);
        ToolGuideAnswer second = guides.answer(resourceId, REF);

        assertThat(model.calls).isEqualTo(2);
        assertThat(second.getStatus()).isEqualTo(ToolGuideStatus.OK);
    }

    private static Material readme() {
        return new Material("readme", MaterialKind.README, URI.create("https://github.com/acme/x"), CLOCK.instant(),
            "Install with:\n\n```bash\nnpm install acme-x\n```\n\n" + README_ONLY + "\n");
    }

    private static final class Model implements ToolGuideModel {
        private final RuntimeException failure;
        int calls;

        Model() {
            this(null);
        }

        Model(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String getModel() {
            return "fake";
        }

        @Override
        public String getConfigFingerprint() {
            return "fp";
        }

        @Override
        public ProposedGuide write(ToolRef ref, List<Material> materials) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new ProposedGuide(List.of(new ProposedSection("INSTALL",
                List.of(new ProposedStep("Install it.", "readme", "Install with:", "npm install acme-x", null)))));
        }
    }
}
