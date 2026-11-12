package com.homehub.domain;

/** Listing lifecycle (design §5.1). */
public enum ListingStatus {
    DRAFT,
    ACTIVE,
    UNDER_OFFER,
    SOLD;

    /** Searchable statuses appear in the sparse city-price GSI (D-2, OQ-4). */
    public boolean isSearchable() {
        return this == ACTIVE || this == UNDER_OFFER;
    }

    /** Publicly visible via GET /listings/{id}. DRAFT is owner-only (needs identity, OQ-1). */
    public boolean isPublic() {
        return this != DRAFT;
    }
}
