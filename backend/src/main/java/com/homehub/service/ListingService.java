package com.homehub.service;

import com.homehub.domain.InvalidRequestException;
import com.homehub.domain.Listing;
import com.homehub.domain.ListingNotFoundException;
import com.homehub.domain.ListingSearch;
import com.homehub.domain.Page;
import com.homehub.port.ListingRepository;
import com.homehub.telemetry.HomeHubTelemetry;
import io.opentelemetry.api.trace.Span;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ListingService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(ListingService.class);

    private final ListingRepository listings;

    public ListingService(ListingRepository listings) {
        this.listings = listings;
    }

    /**
     * R-LIST-02. DRAFT listings are owner-only; until the identity header
     * exists (Phase 2) nobody is the owner, so DRAFT looks like 404 — we
     * don't reveal that an unpublished listing exists.
     */
    public Listing get(String listingId) {
        // "Which resource": the entity id goes on the current (HTTP server) span.
        Span.current().setAttribute(HomeHubTelemetry.LISTING_ID, listingId);
        Listing listing = listings.findById(listingId)
                .filter(l -> l.status().isPublic())
                .orElseThrow(() -> new ListingNotFoundException(listingId));
        // SLF4J key/value pairs become OTel log attributes (agent config in otel-env.sh);
        // trace_id/span_id are attached automatically by the logback bridge.
        log.atInfo().addKeyValue(HomeHubTelemetry.LISTING_ID.getKey(), listingId).log("Listing fetched");
        return listing;
    }

    /** R-SRCH-01: validates criteria, then delegates to the GSI query. */
    public Page<Listing> search(String city, Long minPrice, Long maxPrice, Integer minBeds,
                                Integer limit, String nextToken) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (city == null || city.isBlank()) {
            errors.put("city", "is required");
        }
        if (minPrice != null && minPrice < 0) {
            errors.put("minPrice", "must be >= 0");
        }
        if (maxPrice != null && maxPrice < 0) {
            errors.put("maxPrice", "must be >= 0");
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            errors.put("maxPrice", "must be >= minPrice");
        }
        if (minBeds != null && minBeds < 0) {
            errors.put("minBeds", "must be >= 0");
        }
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            errors.put("limit", "must be between 1 and " + MAX_LIMIT);
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        Page<Listing> page = listings.search(new ListingSearch(
                city, minPrice, maxPrice, minBeds, limit == null ? DEFAULT_LIMIT : limit, nextToken));
        Span.current().setAttribute(HomeHubTelemetry.SEARCH_RESULT_COUNT, (long) page.items().size());
        log.atInfo().addKeyValue(HomeHubTelemetry.SEARCH_RESULT_COUNT.getKey(), page.items().size())
                .log("Listings searched");
        return page;
    }
}
