package ai.helper2.guide.web;

import ai.helper2.api.generated.model.GuideIntentDto;
import ai.helper2.api.generated.model.GuideStreamUnderstoodDto;
import ai.helper2.guide.Clarification;
import ai.helper2.guide.DateWindow;
import ai.helper2.guide.Drops;
import ai.helper2.guide.GuideIntent;
import ai.helper2.api.generated.model.GuideRequestDto;
import ai.helper2.api.generated.model.GuideStreamCheckedDto;
import ai.helper2.api.generated.model.GuideStreamChosenDto;
import ai.helper2.api.generated.model.GuideStreamEventDto;
import ai.helper2.api.generated.model.GuideStreamPlannedDto;
import ai.helper2.api.generated.model.GuideStreamRetrievedDto;
import ai.helper2.api.generated.model.GuideStreamValidatedDto;
import ai.helper2.api.generated.model.ProblemDto;
import ai.helper2.guide.GuideProgress;
import ai.helper2.guide.usecase.AskGuide;
import ai.helper2.platform.ClientKey;
import ai.helper2.platform.SignedInUser;
import ai.helper2.search.SearchOutcome;
import ai.helper2.shared.AudiencePath;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code POST /api/v1/guide/stream} (contract: tag guide-stream). Hand-written because the generator has no
 * SseEmitter return type. Each step of the guide becomes one event, named by the contract's {@code GuideStreamEvent};
 * the last event is always {@code answer} or {@code error}. The work runs on a virtual thread so the servlet thread is released at once.
 *
 * <p>Under the same {@link GuideLimits} as {@code POST /guide}: when every slot is taken the client gets an
 * {@code error} event "busy" at once (status 503), and past the deadline the worker is cancelled and the
 * client gets an {@code error} event (status 504).
 *
 * <p>While a question runs, a {@code :keep-alive} comment is sent every {@code helper2.guide.heartbeat}: one model
 * step can be silent for longer than the client's idle timeout, and an asker who joined an identical question
 * already running gets no step events at all. The comments come from one shared scheduler thread.
 *
 * <p>The guide's latency target is the time to the first useful event (ADR 0026): from the request to the first
 * {@code retrieved} (the results the guide reads) or {@code clarify} (the question back), timed once per question as
 * {@code helper2.guide.first_useful{intent}}. A cached or shared answer has no step events and is not timed.
 */
@RestController
@RequestMapping("/api/v1")
class GuideStreamController implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GuideStreamController.class);
    /** The emitter outlives the deadline a little, so the deadline's error event can still be sent. */
    private static final Duration EMITTER_GRACE = Duration.ofSeconds(10);
    static final String FIRST_USEFUL = "helper2.guide.first_useful";

    private final AskGuide guide;
    private final GuideDtoMapper mapper;
    private final GuideLimits limits;
    private final Duration heartbeat;
    private final SignedInUser signedInUser;
    private final MeterRegistry meters;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("guide-heartbeat").daemon().factory());

    GuideStreamController(AskGuide guide, GuideDtoMapper mapper, GuideLimits limits, SignedInUser signedInUser, MeterRegistry meters,
                          @Value("${helper2.guide.heartbeat:PT15S}") Duration heartbeat) {
        this.guide = guide;
        this.mapper = mapper;
        this.limits = limits;
        this.signedInUser = signedInUser;
        this.meters = meters;
        this.heartbeat = heartbeat;
    }

    @Override
    public void destroy() {
        heartbeats.shutdownNow();
    }

    /**
     * The method name is the rate-limit key: it shares the budget of askGuide (see RateLimitInterceptor).
     * {@code @Valid} applies the contract's bounds (500 characters at most) before any model work starts.
     */
    @PostMapping(path = "/guide/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> askGuideStream(@Valid @RequestBody GuideRequestDto body, HttpServletRequest http) {
        String request = GuideController.validRequest(body);
        AudiencePath path = GuideController.path(body);
        String client = ClientKey.of(http.getRemoteAddr());
        UUID userId = signedInUser.id().orElse(null);
        Events events = new Events(new SseEmitter(limits.deadline().plus(EMITTER_GRACE).toMillis()));
        String clarification = GuideController.clarification(body);
        Drops drops = GuideController.drops(body);
        boolean started = limits.tryStart(() -> run(events, request, path, client, userId, clarification, drops),
            () -> events.fail(504, "Guide timed out", GuideLimits.TIMED_OUT));
        if (started) {
            events.keepAlive();
        } else {
            events.fail(503, "Guide busy", GuideLimits.BUSY);
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            // nginx-style proxies buffer responses by default, which would deliver every event at the end.
            .header("X-Accel-Buffering", "no")
            .body(events.emitter);
    }

    private void run(Events events, String request, AudiencePath path, String client, UUID userId, String clarification, Drops drops) {
        try {
            var answer = guide.ask(request, path, events, client, clarification, drops, userId);
            events.finish(GuideStreamEventDto.ANSWER, mapper.toDto(answer));
        } catch (Throwable e) {
            // Throwable, not only RuntimeException: the emitter must complete whatever happened (an interrupt, an Error).
            log.warn("guide stream failed: {}", e.getClass().getSimpleName());
            events.fail(500, "Guide failed", "The guide could not answer. Try again in a moment.");
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Sends each step as it happens, and exactly one last event ({@code answer} or {@code error}) whichever of
     * the worker and the deadline gets there first. A closed connection is not an error: the answer is still cached.
     */
    private final class Events implements GuideProgress {
        private final SseEmitter emitter;
        private final long startedNanos = System.nanoTime();
        private boolean open = true;
        private boolean finished;
        private boolean usefulSent;
        private String intent = "unknown";
        private ScheduledFuture<?> keepAlive;

        Events(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void planned(String searchQuery, List<String> constraints) {
            send(GuideStreamEventDto.PLANNED, new GuideStreamPlannedDto(searchQuery, constraints).model(mapper.fastModel()));
        }

        @Override
        public void understood(GuideIntent intent, List<String> subjects, DateWindow window) {
            this.intent = intent.name().toLowerCase(Locale.ROOT);
            send(GuideStreamEventDto.UNDERSTOOD, new GuideStreamUnderstoodDto(GuideIntentDto.fromValue(intent.name()), subjects)
                .window(GuideDtoMapper.window(window)));
        }

        @Override
        public void clarify(Clarification clarification) {
            useful(GuideStreamEventDto.CLARIFY, GuideDtoMapper.clarification(clarification));
        }

        @Override
        public void retrieved(int candidates, SearchOutcome search) {
            useful(GuideStreamEventDto.RETRIEVED, new GuideStreamRetrievedDto(candidates, GuideDtoMapper.statuses(search)));
        }

        @Override
        public void chosen(int proposed) {
            send(GuideStreamEventDto.CHOSEN, new GuideStreamChosenDto(proposed).model(mapper.reasoningModel()));
        }

        @Override
        public void validated(int kept, int rejected) {
            send(GuideStreamEventDto.VALIDATED, new GuideStreamValidatedDto(kept, rejected));
        }

        @Override
        public void reasonsChecked(int supported, int removed, int notChecked) {
            send(GuideStreamEventDto.CHECKED, new GuideStreamCheckedDto(supported, removed, notChecked));
        }

        /** Sends an event that shows the asker something real; the first one sent is the question's first useful event. */
        private synchronized void useful(GuideStreamEventDto name, Object data) {
            if (send(name, data) && !usefulSent) {
                usefulSent = true;
                Timer.builder(FIRST_USEFUL)
                    .description("Time from the guide request to its first useful stream event (retrieved or clarify)")
                    .tag("intent", intent)
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(Duration.ofSeconds(3), Duration.ofSeconds(8))
                    .minimumExpectedValue(Duration.ofMillis(100))
                    .maximumExpectedValue(Duration.ofSeconds(30))
                    .register(meters)
                    .record(Duration.ofNanos(System.nanoTime() - startedNanos));
            }
        }

        /** Sends a comment every heartbeat until the last event (or the client is gone). */
        synchronized void keepAlive() {
            if (open && !finished) {
                long every = heartbeat.toMillis();
                keepAlive = heartbeats.scheduleAtFixedRate(this::comment, every, every, TimeUnit.MILLISECONDS);
            }
        }

        private synchronized void comment() {
            if (!open || finished) {
                keepAlive.cancel(false);
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                open = false;
            }
        }

        void fail(int status, String title, String detail) {
            finish(GuideStreamEventDto.ERROR, new ProblemDto().type("about:blank").title(title).status(status).detail(detail));
        }

        synchronized void finish(GuideStreamEventDto name, Object data) {
            if (finished) {
                return;
            }
            send(name, data);
            finished = true;
            if (keepAlive != null) {
                keepAlive.cancel(false);
            }
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                // Already completed by the container (client gone, emitter timeout): nothing left to send.
            }
        }

        /** True when the event reached the connection. */
        synchronized boolean send(GuideStreamEventDto name, Object data) {
            if (!open || finished) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(name.getValue()).data(data, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                open = false;
                return false;
            }
        }
    }
}
