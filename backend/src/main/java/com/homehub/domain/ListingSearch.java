package com.homehub.domain;

/**
 * Validated search criteria. {@code city} is required because it is the GSI
 * partition key: DynamoDB can only Query within one partition.
 */
public record ListingSearch(String city, Long minPrice, Long maxPrice, Integer minBeds, int limit, String nextToken) {
}
