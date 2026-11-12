package com.homehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homehub.TestListings;
import com.homehub.domain.InvalidRequestException;
import com.homehub.domain.ListingNotFoundException;
import com.homehub.domain.ListingSearch;
import com.homehub.domain.ListingStatus;
import com.homehub.domain.Page;
import com.homehub.port.ListingRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ListingServiceTest {

    private final ListingRepository repo = mock(ListingRepository.class);
    private final ListingService service = new ListingService(repo);

    @ParameterizedTest
    @EnumSource(value = ListingStatus.class, names = {"ACTIVE", "UNDER_OFFER", "SOLD"})
    @DisplayName("R-LIST-02 returns public listings")
    void getPublic(ListingStatus status) {
        var listing = TestListings.listing("LST-0001", status);
        when(repo.findById("LST-0001")).thenReturn(Optional.of(listing));

        assertThat(service.get("LST-0001")).isEqualTo(listing);
    }

    @Test
    @DisplayName("R-LIST-02 DRAFT looks like 404 until owner identity exists")
    void getDraftIsNotFound() {
        when(repo.findById("LST-0004")).thenReturn(Optional.of(TestListings.listing("LST-0004", ListingStatus.DRAFT)));

        assertThatThrownBy(() -> service.get("LST-0004"))
                .isInstanceOf(ListingNotFoundException.class)
                .satisfies(e -> assertThat(((ListingNotFoundException) e).listingId()).isEqualTo("LST-0004"));
    }

    @Test
    void getUnknownIsNotFound() {
        when(repo.findById("LST-9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("LST-9999")).isInstanceOf(ListingNotFoundException.class);
    }

    @Test
    @DisplayName("R-SRCH-01.4 applies default limit and passes criteria through")
    void searchDefaults() {
        var page = new Page<>(List.of(TestListings.listing("LST-0001", ListingStatus.ACTIVE)), null);
        when(repo.search(any())).thenReturn(page);

        assertThat(service.search("Seattle", 100L, 900L, 2, null, "tok")).isSameAs(page);
        verify(repo).search(new ListingSearch("Seattle", 100L, 900L, 2, ListingService.DEFAULT_LIMIT, "tok"));
    }

    @Test
    void searchHonoursExplicitLimit() {
        when(repo.search(any())).thenReturn(new Page<>(List.of(), null));

        service.search("Seattle", null, null, null, 100, null);

        verify(repo).search(new ListingSearch("Seattle", null, null, null, 100, null));
    }

    @Test
    @DisplayName("R-SRCH-01.1 city is required")
    void searchRequiresCity() {
        assertThatThrownBy(() -> service.search(null, null, null, null, null, null))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.fieldErrors()).containsEntry("city", "is required"));
        assertThatThrownBy(() -> service.search("  ", null, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(repo, never()).search(any());
    }

    @Test
    void searchCollectsAllFieldErrors() {
        assertThatThrownBy(() -> service.search("x", -1L, -2L, -3, 0, null))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.fieldErrors())
                        .containsKeys("minPrice", "maxPrice", "minBeds", "limit"));
    }

    @Test
    void searchRejectsInvertedPriceRangeAndOversizeLimit() {
        assertThatThrownBy(() -> service.search("x", 900L, 100L, null, 101, null))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("maxPrice", "must be >= minPrice")
                        .containsEntry("limit", "must be between 1 and 100"));
    }
}
