package com.homehub.telemetry;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Span/log attribute names (design §8). One place, so names never drift
 * between code, tests and the telemetry-oracle assertions.
 *
 * <p>Entity ids are span ATTRIBUTES. In OTel, "Resource" means the service
 * emitting telemetry (service.name, service.version) — not domain entities.
 */
public final class HomeHubTelemetry {

    // Who invoked (set once per request by ActorFilter).
    // enduser.id is what CLAUDE.md specifies; newer OTel semconv renames it
    // to user.id — revisit if the collector/backends standardize on that.
    public static final AttributeKey<String> ENDUSER_ID = AttributeKey.stringKey("enduser.id");
    public static final AttributeKey<String> ACTOR_ROLE = AttributeKey.stringKey("homehub.actor.role");
    public static final AttributeKey<String> CLIENT_ADDRESS = AttributeKey.stringKey("client.address");
    public static final AttributeKey<String> USER_AGENT = AttributeKey.stringKey("user_agent.original");

    // Which resource (domain entity ids).
    public static final AttributeKey<String> LISTING_ID = AttributeKey.stringKey("homehub.listing.id");

    // Juncture-specific.
    public static final AttributeKey<Long> SEARCH_RESULT_COUNT = AttributeKey.longKey("homehub.search.result_count");
    public static final AttributeKey<Boolean> RESOURCE_CREATED = AttributeKey.booleanKey("homehub.resource.created");
    public static final AttributeKey<Long> SEED_LISTINGS = AttributeKey.longKey("homehub.seed.listings");

    // Span event names.
    public static final String EVENT_SEED_SKIPPED = "seed.skipped";

    private HomeHubTelemetry() {
    }
}
