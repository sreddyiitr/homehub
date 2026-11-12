package com.homehub.api;

import com.homehub.domain.InvalidRequestException;
import com.homehub.domain.ListingNotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to RFC 9457 problem+json (D-6). */
@RestControllerAdvice
public class ProblemHandler {

    @ExceptionHandler(ListingNotFoundException.class)
    ProblemDetail notFound(ListingNotFoundException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        p.setTitle("Listing not found");
        return p;
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalid(InvalidRequestException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        p.setTitle("Invalid request");
        List<Map<String, String>> errors = e.fieldErrors().entrySet().stream()
                .map(en -> Map.of("field", en.getKey(), "message", en.getValue()))
                .toList();
        p.setProperty("errors", errors);
        return p;
    }
}
