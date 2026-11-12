package com.homehub.bootstrap;

import static com.homehub.config.HomeHubResources.SEED_SENTINEL_LISTING_ID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homehub.config.HomeHubProperties;
import com.homehub.domain.Listing;
import com.homehub.port.ListingRepository;
import com.homehub.telemetry.HomeHubTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startup step 2 (local profile only): load deterministic fixtures from
 * test/data/. Skips when the sentinel listing already exists, so restarting
 * the app against a still-running emulator doesn't rewrite data.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "homehub.seed-data", havingValue = "true")
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final ListingRepository listings;
    private final ObjectMapper json;
    private final Path seedDir;

    public SeedDataLoader(ListingRepository listings, ObjectMapper json, HomeHubProperties props) {
        this.listings = listings;
        this.json = json;
        this.seedDir = Path.of(props.seedPath());
    }

    @Override
    @WithSpan("load-seed-data")
    public void run(ApplicationArguments args) throws IOException {
        if (listings.findById(SEED_SENTINEL_LISTING_ID).isPresent()) {
            Span.current().addEvent(HomeHubTelemetry.EVENT_SEED_SKIPPED);
            log.info("Seed sentinel {} present, skipping seed", SEED_SENTINEL_LISTING_ID);
            return;
        }
        List<Listing> fixtures = json.readValue(seedDir.resolve("listings.json").toFile(), new TypeReference<>() {
        });
        fixtures.forEach(listings::put);
        Span.current().setAttribute(HomeHubTelemetry.SEED_LISTINGS, (long) fixtures.size());
        log.info("Seeded {} listings from {}", fixtures.size(), seedDir.toAbsolutePath().normalize());
    }
}
