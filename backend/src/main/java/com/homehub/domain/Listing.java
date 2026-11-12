package com.homehub.domain;

import java.time.Instant;

/** A property listing. Money is whole USD (D-5). No AWS types here (R-ARCH-01). */
public record Listing(
        String listingId,
        String agentId,
        ListingStatus status,
        String address,
        String city,
        String state,
        String zip,
        long price,
        int beds,
        double baths,
        int sqft,
        String description,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
