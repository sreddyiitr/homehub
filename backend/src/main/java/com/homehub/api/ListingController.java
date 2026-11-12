package com.homehub.api;

import com.homehub.domain.Listing;
import com.homehub.domain.Page;
import com.homehub.service.ListingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/listings")
public class ListingController {

    private final ListingService service;

    public ListingController(ListingService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public ListingSearchResponse search(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) Integer minBeds,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String nextToken) {
        Page<Listing> page = service.search(city, minPrice, maxPrice, minBeds, limit, nextToken);
        return new ListingSearchResponse(page.items().stream().map(ListingResponse::from).toList(), page.nextToken());
    }

    /** ETag carries the optimistic-lock version; PATCH will require it in If-Match (Phase 3). */
    @GetMapping("/{id}")
    public ResponseEntity<ListingResponse> get(@PathVariable String id) {
        Listing listing = service.get(id);
        return ResponseEntity.ok().eTag(Long.toString(listing.version())).body(ListingResponse.from(listing));
    }
}
