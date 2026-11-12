package com.homehub.api;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** {@code nextToken} is absent on the last page. */
public record ListingSearchResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<ListingResponse> items,
        String nextToken) {
}
