package com.homehub.config;

/**
 * The ONE place AWS resource names live (R-CFG-03). Bootstrap, repositories,
 * seed loader and tests all reference these constants.
 */
public final class HomeHubResources {

    public static final String LISTINGS_TABLE = "Listings";
    /** Sparse GSI: PK searchCity (present only for searchable statuses), SK price. */
    public static final String LISTINGS_CITY_PRICE_INDEX = "city-price-index";
    /** GSI: PK agentId, SK createdAt. */
    public static final String LISTINGS_AGENT_INDEX = "agent-index";

    /** Seeding is skipped when this fixture listing already exists. */
    public static final String SEED_SENTINEL_LISTING_ID = "LST-0001";

    private HomeHubResources() {
    }
}
