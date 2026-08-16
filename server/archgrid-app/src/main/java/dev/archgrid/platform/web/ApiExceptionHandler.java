package dev.archgrid.platform.web;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
    private static final URI INTERNAL_ERROR = URI.create("https://archgrid.dev/problems/internal-error");

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
