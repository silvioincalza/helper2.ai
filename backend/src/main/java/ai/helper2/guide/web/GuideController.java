package ai.helper2.guide.web;

import ai.helper2.api.generated.GuideApi;
import ai.helper2.api.generated.model.GuideRequestDto;
import ai.helper2.api.generated.model.GuideResponseDto;
import ai.helper2.guide.GuideAnswer;
import ai.helper2.guide.GuideProgress;
import ai.helper2.guide.usecase.AskGuide;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.ClientKey;
import ai.helper2.shared.AudiencePath;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /api/v1/guide}: the whole answer as one JSON body. Under the same {@link GuideLimits} as the
 * stream: 503 at once when every slot is taken, 504 when the question passes its deadline.
 */
@RestController
@RequestMapping("/api/v1")
class GuideController implements GuideApi {

    private final AskGuide guide;
    private final GuideDtoMapper mapper;
    private final GuideLimits limits;
    /** A proxy to the current request: the generated interface does not pass it. */
    private final HttpServletRequest http;

    GuideController(AskGuide guide, GuideDtoMapper mapper, GuideLimits limits, HttpServletRequest http) {
        this.guide = guide;
        this.mapper = mapper;
        this.limits = limits;
        this.http = http;
    }

    @Override
    public ResponseEntity<GuideResponseDto> askGuide(GuideRequestDto body) {
        String request = validRequest(body);
        AudiencePath path = path(body);
        // Read on the request thread: the request proxy is not bound on the worker.
        String client = ClientKey.of(http.getRemoteAddr());
        CompletableFuture<GuideAnswer> answer = new CompletableFuture<>();
        boolean started = limits.tryStart(() -> {
            try {
                answer.complete(guide.ask(request, path, GuideProgress.NONE, client));
            } catch (Throwable e) {
                answer.completeExceptionally(e);
            }
        }, () -> answer.completeExceptionally(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, GuideLimits.TIMED_OUT)));
        if (!started) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, GuideLimits.BUSY);
        }
        GuideResponseDto dto;
        try {
            dto = mapper.toDto(answer.join());
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dto);
    }

    static String validRequest(GuideRequestDto body) {
        String request = body.getRequest() == null ? "" : body.getRequest().strip();
        if (request.length() < 3) {
            throw new BadRequestException("request too short");
        }
        return request;
    }

    static AudiencePath path(GuideRequestDto body) {
        return body.getPath() == null ? null : AudiencePath.valueOf(body.getPath().getValue());
    }
}
