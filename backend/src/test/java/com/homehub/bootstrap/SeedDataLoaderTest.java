package com.homehub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homehub.TestListings;
import com.homehub.config.HomeHubProperties;
import com.homehub.config.HomeHubResources;
import com.homehub.domain.Listing;
import com.homehub.domain.ListingStatus;
import com.homehub.port.ListingRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Reads the REAL checked-in fixtures (test/data/listings.json) with a mocked repository. */
class SeedDataLoaderTest {

    private final ListingRepository repo = mock(ListingRepository.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HomeHubProperties props = new HomeHubProperties(true, true, "../test/data",
            new HomeHubProperties.Aws(null, "us-west-2", null), new HomeHubProperties.Cors(List.of()));
    private final SeedDataLoader loader = new SeedDataLoader(repo, json, props);

    @Test
    @DisplayName("R-SEED-01.1 skips when the sentinel listing exists")
    void skipsWhenSeeded() throws Exception {
        when(repo.findById(HomeHubResources.SEED_SENTINEL_LISTING_ID))
                .thenReturn(Optional.of(TestListings.listing("LST-0001", ListingStatus.ACTIVE)));

        loader.run(null);

        verify(repo, never()).put(any());
    }

    @Test
    @DisplayName("R-SEED-01 loads every fixture listing, including the edge-case statuses")
    void loadsFixtures() throws Exception {
        when(repo.findById(any())).thenReturn(Optional.empty());

        loader.run(null);

        var captor = ArgumentCaptor.forClass(Listing.class);
        verify(repo, Mockito.atLeastOnce()).put(captor.capture());
        List<Listing> loaded = captor.getAllValues();
        assertThat(loaded).extracting(Listing::listingId).contains(HomeHubResources.SEED_SENTINEL_LISTING_ID);
        assertThat(loaded).extracting(Listing::status).contains(ListingStatus.values());
        assertThat(loaded).allSatisfy(l -> {
            assertThat(l.listingId()).matches("LST-\\d{4}");
            assertThat(l.agentId()).matches("AGT-\\d{3}");
            assertThat(l.price()).isPositive();
        });
    }
}
