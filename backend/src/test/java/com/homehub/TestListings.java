package com.homehub;

import com.homehub.domain.Listing;
import com.homehub.domain.ListingStatus;
import java.time.Instant;

/** Builders for unit tests. Functional tests use the checked-in fixtures in test/data/ instead. */
public final class TestListings {

    public static final Instant CREATED = Instant.parse("2026-10-01T10:00:00Z");

    private TestListings() {
    }

    public static Listing listing(String id, ListingStatus status) {
        return new Listing(id, "AGT-001", status, "101 Example Ave", "Seattle", "WA", "98101",
                850_000, 3, 2.5, 1850, "Craftsman", 1, CREATED, CREATED);
    }
}
