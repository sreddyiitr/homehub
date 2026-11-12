package com.homehub.service;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.homehub.TestListings;
import com.homehub.domain.ListingNotFoundException;
import com.homehub.domain.ListingStatus;
import com.homehub.domain.Page;
import com.homehub.port.ListingRepository;
import com.homehub.telemetry.HomeHubTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** "Which resource" attributes land on the current span (R-OBS-04). */
class ListingServiceTelemetryTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private final ListingRepository repo = mock(ListingRepository.class);
    private final ListingService service = new ListingService(repo);

    private void inSpan(Runnable body) {
        Span span = otel.getOpenTelemetry().getTracer("test").spanBuilder("server").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            body.run();
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("R-OBS-04 listing id on the span, even when the lookup 404s")
    void listingIdAttribute() {
        when(repo.findById("LST-0004")).thenReturn(Optional.of(TestListings.listing("LST-0004", ListingStatus.DRAFT)));

        inSpan(() -> assertThatThrownBy(() -> service.get("LST-0004")).isInstanceOf(ListingNotFoundException.class));

        assertThat(otel.getSpans().get(0)).hasAttribute(HomeHubTelemetry.LISTING_ID, "LST-0004");
    }

    @Test
    void searchResultCountAttribute() {
        when(repo.search(any())).thenReturn(new Page<>(List.of(
                TestListings.listing("LST-0001", ListingStatus.ACTIVE),
                TestListings.listing("LST-0002", ListingStatus.ACTIVE)), null));

        inSpan(() -> service.search("Seattle", null, null, null, null, null));

        assertThat(otel.getSpans().get(0)).hasAttribute(HomeHubTelemetry.SEARCH_RESULT_COUNT, 2L);
    }
}
