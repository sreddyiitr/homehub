package com.homehub.api;

import com.homehub.domain.Listing;
import com.homehub.domain.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/**
 * API shape of a listing. Separate from the domain record so the wire contract
 * can evolve independently. {@code @Schema(REQUIRED)} marks guaranteed fields so
 * the generated TypeScript types aren't all-optional.
 */
public record ListingResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String listingId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String agentId,
        @Schema(requiredMode = RequiredMode.REQUIRED) ListingStatus status,
        @Schema(requiredMode = RequiredMode.REQUIRED) String address,
        @Schema(requiredMode = RequiredMode.REQUIRED) String city,
        @Schema(requiredMode = RequiredMode.REQUIRED) String state,
        @Schema(requiredMode = RequiredMode.REQUIRED) String zip,
        @Schema(requiredMode = RequiredMode.REQUIRED) long price,
        @Schema(requiredMode = RequiredMode.REQUIRED) int beds,
        @Schema(requiredMode = RequiredMode.REQUIRED) double baths,
        @Schema(requiredMode = RequiredMode.REQUIRED) int sqft,
        String description,
        @Schema(requiredMode = RequiredMode.REQUIRED) long version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

    static ListingResponse from(Listing l) {
        return new ListingResponse(l.listingId(), l.agentId(), l.status(), l.address(), l.city(), l.state(),
                l.zip(), l.price(), l.beds(), l.baths(), l.sqft(), l.description(), l.version(),
                l.createdAt(), l.updatedAt());
    }
}
