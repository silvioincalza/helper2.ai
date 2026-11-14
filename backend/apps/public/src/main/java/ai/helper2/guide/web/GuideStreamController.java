package ai.helper2.guide.web;

import ai.helper2.api.common.model.ProblemDto;
import ai.helper2.api.generated.model.GuideIntentDto;
import ai.helper2.api.generated.model.GuideModelStepDto;
import ai.helper2.api.generated.model.GuideRequestDto;
import ai.helper2.api.generated.model.GuideStreamCheckedDto;
import ai.helper2.api.generated.model.GuideStreamChosenDto;
import ai.helper2.api.generated.model.GuideStreamClarifyDto;
import ai.helper2.api.generated.model.GuideStreamEventDto;
import ai.helper2.api.generated.model.GuideStreamModelStepDto;
import ai.helper2.api.generated.model.GuideStreamPlannedDto;
import ai.helper2.api.generated.model.GuideStreamRetrievedDto;
import ai.helper2.api.generated.model.GuideStreamUnderstoodDto;
import ai.helper2.api.generated.model.GuideStreamValidatedDto;
import ai.helper2.guide.Clarification;
import ai.helper2.guide.DateWindow;
import ai.helper2.guide.Drops;
import ai.helper2.guide.GuideBusy;
import ai.helper2.guide.GuideIntent;
import ai.helper2.guide.GuideProgress;
import ai.helper2.guide.ModelStep;
import ai.helper2.guide.WorkGate;
import ai.helper2.guide.config.GuideProperties;
import ai.helper2.guide.usecase.AskGuide;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.ClientKey;
import ai.helper2.platform.ProblemType;
import ai.helper2.search.SearchOutcome;
import ai.helper2.shared.AudiencePath;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.Presence;
import ai.helper2.shared.SingleFlight;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@RestController
@RequestMapping("/api/v1")
class GuideStreamController {

  private static final Logger log = LoggerFactory.getLogger(GuideStreamController.class);

  private static final Duration STREAM_GRACE = Duration.ofSeconds(10);

  private static final ServerSentEvent<Object> KEEP_ALIVE =
      ServerSentEvent.builder().comment("keep-alive").build();

  static final String FIRST_USEFUL = "helper2.guide.first_useful";

  private final AskGuide guide;
  private final GuideDtoMapper mapper;
  private final GuideLimits limits;
  private final Duration heartbeat;
  private final MeterRegistry meters;
  private final CategoryCatalog categories;

  @Autowired
  GuideStreamController(
      AskGuide guide,
      GuideDtoMapper mapper,
      GuideLimits limits,
      MeterRegistry meters,
      GuideProperties settings,
      CategoryCatalog categories) {
    this(guide, mapper, limits, meters, settings.heartbeat(), categories);
  }

  GuideStreamController(
      AskGuide guide,
      GuideDtoMapper mapper,
      GuideLimits limits,
      MeterRegistry meters,
      Duration heartbeat) {
    this(guide, mapper, limits, meters, heartbeat, CategoryCatalog.EMPTY);
  }

  GuideStreamController(
      AskGuide guide,
      GuideDtoMapper mapper,
      GuideLimits limits,
      MeterRegistry meters,
      Duration heartbeat,
      CategoryCatalog categories) {
    this.categories = categories;
    this.guide = guide;
    this.mapper = mapper;
    this.limits = limits;
    this.meters = meters;
    this.heartbeat = heartbeat;
  }

  @PostMapping(
      path = "/guide/stream",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<Flux<ServerSentEvent<Object>>> askGuideStream(
      @Valid @RequestBody GuideRequestDto body, HttpServletRequest http) {
    String request = validRequest(body);
    AudiencePath path = path(body);
    String client = ClientKey.of(http.getRemoteAddr());
    Events events = new Events();
    String clarification = clarification(body);
    Drops drops = drops(body);
    Set<String> categories = categories(body);
    Presence asker = new Presence();
    Asked asked = new Asked(request, path, client, clarification, drops, categories, asker);
    boolean started =
        limits.tryStart(
            client,
            gate -> run(events, asked, gate),
            () -> events.fail(ProblemType.TIMED_OUT, GuideLimits.TIMED_OUT));
    if (!started) {
      events.fail(ProblemType.UNAVAILABLE, GuideLimits.BUSY);
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Accel-Buffering", "no")
        .body(
            withKeepAlive(events.flux())
                .take(limits.lastAnswerBy().plus(STREAM_GRACE))
                .doOnCancel(() -> limits.whenTheAskerStaysAway(asker)));
  }

  private Flux<ServerSentEvent<Object>> withKeepAlive(Flux<ServerSentEvent<Object>> events) {
    Flux<ServerSentEvent<Object>> beats =
        Flux.interval(heartbeat, heartbeat).map(tick -> KEEP_ALIVE);
    return events.publish(shared -> Flux.merge(shared, beats.takeUntilOther(shared.then())));
  }

  static String validRequest(GuideRequestDto body) {
    String request = body.getRequest() == null ? "" : body.getRequest().strip();
    if (request.length() < 3) {
      throw new BadRequestException("request too short");
    }
    return request;
  }

  static String clarification(GuideRequestDto body) {
    return body.getClarification() == null ? null : body.getClarification().strip();
  }

  static Drops drops(GuideRequestDto body) {
    return Drops.of(body.getDrop());
  }

  Set<String> categories(GuideRequestDto body) {
    Set<String> selected =
        body.getCategories() == null ? Set.of() : Set.copyOf(body.getCategories());
    List<String> unknown = categories.notFilterValues(selected);
    if (!unknown.isEmpty()) {
      throw new BadRequestException("Unknown category: " + String.join(", ", unknown));
    }
    return selected;
  }

  static AudiencePath path(GuideRequestDto body) {
    return body.getPath() == null ? null : AudiencePath.valueOf(body.getPath().getValue());
  }

  private record Asked(
      String request,
      AudiencePath path,
      String client,
      String clarification,
      Drops drops,
      Set<String> categories,
      Presence asker) {}

  private void run(Events events, Asked asked, WorkGate gate) {
    try {
      var answer =
          guide.ask(
              asked.request(),
              asked.path(),
              events,
              asked.client(),
              asked.clarification(),
              asked.drops(),
              asked.categories(),
              gate,
              asked.asker());
      events.finish(GuideStreamEventDto.ANSWER, mapper.toDto(answer));
    } catch (GuideBusy e) {
      events.fail(ProblemType.UNAVAILABLE, GuideLimits.BUSY);
    } catch (SingleFlight.Abandoned e) {
      log.info("guide stream ended with no answer: its asker left");
    } catch (Throwable e) {
      if (limits.deadlineIsOver()) {
        log.warn("guide stream had no answer at its deadline: {}", e.getClass().getSimpleName());
        events.fail(ProblemType.TIMED_OUT, GuideLimits.TIMED_OUT);
      } else {
        log.warn("guide stream failed: {}", e.getClass().getSimpleName());
        events.fail(ProblemType.INTERNAL, "The guide could not answer. Try again in a moment.");
      }
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private final class Events implements GuideProgress {
    private final Sinks.Many<ServerSentEvent<Object>> sink =
        Sinks.many().unicast().onBackpressureBuffer();
    private final long startedNanos = System.nanoTime();
    private boolean finished;
    private boolean usefulSent;
    private String intent = "unknown";

    Flux<ServerSentEvent<Object>> flux() {
      return sink.asFlux();
    }

    @Override
    public void planned(String searchQuery, List<String> constraints) {
      send(
          GuideStreamEventDto.PLANNED,
          new GuideStreamPlannedDto(searchQuery, constraints)
              .model(mapper.fastModel())
              .aiContent(mapper.plannedMarker(searchQuery, constraints)));
    }

    @Override
    public void understood(GuideIntent intent, List<String> subjects, DateWindow window) {
      this.intent = intent.name().toLowerCase(Locale.ROOT);
      send(
          GuideStreamEventDto.UNDERSTOOD,
          new GuideStreamUnderstoodDto(GuideIntentDto.fromValue(intent.name()), subjects)
              .window(GuideDtoMapper.window(window)));
    }

    @Override
    public void clarify(Clarification clarification) {
      useful(
          GuideStreamEventDto.CLARIFY,
          new GuideStreamClarifyDto(clarification.getQuestion(), clarification.getOptions())
              .aiContent(mapper.clarifyMarker(clarification)));
    }

    @Override
    public void retrieved(int candidates, SearchOutcome search) {
      useful(
          GuideStreamEventDto.RETRIEVED,
          new GuideStreamRetrievedDto(candidates, GuideDtoMapper.statuses(search)));
    }

    @Override
    public void chosen(int proposed) {
      send(
          GuideStreamEventDto.CHOSEN,
          new GuideStreamChosenDto(proposed).model(mapper.reasoningModel()));
    }

    @Override
    public void validated(int kept, int rejected) {
      send(GuideStreamEventDto.VALIDATED, new GuideStreamValidatedDto(kept, rejected));
    }

    @Override
    public void reasonsChecked(int supported, int removed, int notChecked) {
      send(GuideStreamEventDto.CHECKED, new GuideStreamCheckedDto(supported, removed, notChecked));
    }

    @Override
    public void reasoning(ModelStep step) {
      send(GuideStreamEventDto.REASONING, modelStep(step));
    }

    @Override
    public void writing(ModelStep step) {
      send(GuideStreamEventDto.WRITING, modelStep(step));
    }

    private static GuideStreamModelStepDto modelStep(ModelStep step) {
      return new GuideStreamModelStepDto(GuideModelStepDto.fromValue(step.getLabel()));
    }

    private synchronized void useful(GuideStreamEventDto name, Object data) {
      if (send(name, data) && !usefulSent) {
        usefulSent = true;
        Timer.builder(FIRST_USEFUL)
            .description(
                "Time from the guide request to its first useful stream event (retrieved or clarify)")
            .tag("intent", intent)
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofSeconds(3), Duration.ofSeconds(8))
            .minimumExpectedValue(Duration.ofMillis(100))
            .maximumExpectedValue(Duration.ofSeconds(30))
            .register(meters)
            .record(Duration.ofNanos(System.nanoTime() - startedNanos));
      }
    }

    void fail(ProblemType type, String detail) {
      finish(
          GuideStreamEventDto.ERROR,
          new ProblemDto()
              .type(type.uri())
              .title(type.title())
              .status(type.status().value())
              .detail(detail)
              .code(type.code()));
    }

    synchronized void finish(GuideStreamEventDto name, Object data) {
      if (finished) {
        return;
      }
      send(name, data);
      finished = true;
      sink.tryEmitComplete();
    }

    synchronized boolean send(GuideStreamEventDto name, Object data) {
      if (finished) {
        return false;
      }
      return sink.tryEmitNext(ServerSentEvent.builder(data).event(name.getValue()).build())
          .isSuccess();
    }
  }
}
