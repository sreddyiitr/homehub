package com.homehub.domain;

import java.util.Map;

/** Client input failed validation; rendered as 400 problem+json with field errors. */
public class InvalidRequestException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public InvalidRequestException(Map<String, String> fieldErrors) {
        super("Invalid request: " + fieldErrors);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public static InvalidRequestException of(String field, String message) {
        return new InvalidRequestException(Map.of(field, message));
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
