package dev.archgrid.model;

import dev.archgrid.domain.model.Violation;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a refused change into a response an architect can act on.
 *
 * <p>Lives in the model slice rather than in {@code platform.web} so that the platform stays
 * free of feature knowledge. Spring applies both advices; this one is more specific and wins
 * for the exceptions it declares.
 */
@RestControllerAdvice(assignableTypes = ModelController.class)
class ModelExceptionHandler {

    private static final URI INVALID_MODEL = URI.create("https://archgrid.dev/problems/model-would-be-invalid");
    private static final URI STALE_BASE = URI.create("https://archgrid.dev/problems/stale-base-commit");

    /**
     * 422 rather than 400: the request was well-formed and understood, it is the resulting model
     * that would be wrong. The distinction matters to a client deciding whether to retry.
     */
    @ExceptionHandler(ModelCommandService.ModelRejectedException.class)
    ProblemDetail onRejected(ModelCommandService.ModelRejectedException ex) {
        List<Violation> violations = ex.violations();
        // UNPROCESSABLE_CONTENT, not UNPROCESSABLE_ENTITY: RFC 9110 renamed 422 and Spring 7
        // deprecated the old constant. Same status code.
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, summarise(violations));
        problem.setTitle("The change would make the model invalid");
        problem.setType(INVALID_MODEL);
        problem.setProperty(
                "violations",
                violations.stream()
                        .map(violation -> Map.of(
                                "code", violation.code(),
                                "subject", violation.subject(),
                                "detail", violation.detail()))
                        .toList());
        return problem;
    }

    /** 409 with both commit ids, so the client can fetch the diff and rebase rather than guess. */
    @ExceptionHandler(ModelCommandService.StaleBaseCommitException.class)
    ProblemDetail onStaleBase(ModelCommandService.StaleBaseCommitException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("The model has changed");
        problem.setType(STALE_BASE);
        problem.setProperty("expectedBaseCommitId", ex.expected());
        problem.setProperty("currentHeadCommitId", ex.actual());
        return problem;
    }

    private static String summarise(List<Violation> violations) {
        if (violations.size() == 1) {
            return violations.get(0).detail();
        }
        return violations.size() + " problems prevent this change: "
                + violations.stream().map(Violation::detail).reduce((a, b) -> a + " " + b).orElse("");
    }
}
