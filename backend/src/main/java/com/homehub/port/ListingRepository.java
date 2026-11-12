package com.homehub.port;

import com.homehub.domain.Listing;
import com.homehub.domain.ListingSearch;
import com.homehub.domain.Page;
import java.util.Optional;

/**
 * Persistence port for listings. Services depend on this interface, not on
 * DynamoDB, which keeps AWS SDK types out of the service/domain layers and
 * lets unit tests mock at this seam.
 */
public interface ListingRepository {

    Optional<Listing> findById(String listingId);

    /** Searchable listings in one city, price ascending. Must be a Query, never a Scan (R-SRCH-02). */
    Page<Listing> search(ListingSearch search);

    /** Unconditional write; used by seeding only. Conditional updates arrive in Phase 3. */
    void put(Listing listing);
}
