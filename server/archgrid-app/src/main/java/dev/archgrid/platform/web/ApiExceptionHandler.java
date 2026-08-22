package dev.archgrid.platform.web;

import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures to RFC 9457 problem responses.
 *
 * <p>The convention matters more than it looks. From phase 2 onward, the most common
 * error in this system is "your change would make the model invalid", and that response
 * has to carry which metamodel rule was violated and which relationships would have been
 * allowed instead. A product whose validator only says "no" teaches nobody; establishing
 * a structured error shape now means the validator has somewhere to put that detail.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final URI INVALID_REQUEST = URI.create("https://archgrid.dev/problems/invalid-request");
    private static final URI CONFLICT = URI.create("https://archgrid.dev/problems/conflict");
    private static final URI INTERNAL_ERROR = URI.create("https://archgrid.dev/problems/internal-error");

    /**
     * What each uniqueness index guards, in words the person who hit it can act on.
     *
     * <p>The validator in {@code ModelChange} normally says this first and better, naming the
     * object that already holds the name; the index is the last line of defence (docs/09) and
     * only speaks when two writers raced or something bypassed the pipeline. Even then the answer
     * is a sentence, not a constraint name.
     */
    private static final Map<String, String> CONSTRAINTS = Map.of(
            "element_name_unique_per_model",
                    "An element with that name already exists in this repository. Element names are unique.",
            "view_name_unique_per_model",
                    "A view with that name already exists in this repository. View names are unique.",
            "relationship_triple_unique_per_model",
                    "A relationship of that type between those two elements already exists. "
                            + "One relationship of a type between two elements; name or annotate the existing one.");

    /**
     * Hands security and status-carrying exceptions back to the machinery that knows how to
     * translate them.
     *
     * <p>Without this, the catch-all below swallows them. {@link AccessDeniedException} has to
     * reach Spring Security's {@code ExceptionTranslationFilter} to become a 403, and
     * {@link ErrorResponseException} — which {@code ResponseStatusException} extends — already
     * carries its own status and problem document. Catching either turns a correct 403 or 404
     * into "Internal error", which is both wrong and, for the authorisation case, alarming in a
     * security review.
     *
     * <p>Declared before the catch-all deliberately: handler selection prefers the most specific
     * match, and these must win.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class, ErrorResponseException.class})
    void rethrowForFrameworkTranslation(Exception ex) throws Exception {
        throw ex;
    }

    /**
     * Domain value objects reject malformed input by throwing. Surfacing that as 400 keeps
     * validation in one place instead of duplicating every rule in a request DTO.
     */
    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
    ProblemDetail onInvalidArgument(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        problem.setType(INVALID_REQUEST);
        return problem;
    }

    /**
     * The database refused a write its constraints forbid: a 409, never a 500.
     *
     * <p>This is the case the uniqueness indexes exist for — two commits racing to create the same
     * name, an identifier already taken — and the caller's remedy is to reload and look, which is
     * what a conflict status tells a client to do. Reporting it as an internal error would send
     * them to the logs for a problem that is theirs to resolve.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail onConflict(DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        String detail = CONSTRAINTS.entrySet().stream()
                .filter(entry -> cause != null && cause.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("The change conflicts with what the repository already holds. Reload and try again.");
        log.warn("Write refused by a database constraint: {}", cause);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle("Conflict");
        problem.setType(CONFLICT);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception ex) {
        // Log the cause, return none of it: exception text from an architecture repository
        // can leak element names and connection details to a caller who may not be
        // authorized to see them.
        log.error("Unhandled exception while serving request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
        problem.setTitle("Internal error");
        problem.setType(INTERNAL_ERROR);
        return problem;
    }
}
