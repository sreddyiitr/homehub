package com.homehub.bootstrap;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startup step 1 (local profile only): create infra that doesn't exist yet.
 * The emulator is in-memory, so this re-runs on every start and must be
 * idempotent. Against real AWS this bean doesn't exist — infra comes from
 * infra/cfn/. The slice only creates the Listings table; Phase 2+ adds the rest.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "homehub.bootstrap-infra", havingValue = "true")
public class InfraBootstrapRunner implements ApplicationRunner {

    private final ListingsTableBootstrap listingsTable;

    public InfraBootstrapRunner(ListingsTableBootstrap listingsTable) {
        this.listingsTable = listingsTable;
    }

    /** Critical juncture: own span. The AWS SDK calls inside appear as its children. */
    @Override
    @WithSpan("bootstrap-infra")
    public void run(ApplicationArguments args) {
        listingsTable.ensureTable();
    }
}
