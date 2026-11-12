package com.homehub.domain;

public class ListingNotFoundException extends RuntimeException {

    private final String listingId;

    public ListingNotFoundException(String listingId) {
        super("Listing not found: " + listingId);
        this.listingId = listingId;
    }

    public String listingId() {
        return listingId;
    }
}
