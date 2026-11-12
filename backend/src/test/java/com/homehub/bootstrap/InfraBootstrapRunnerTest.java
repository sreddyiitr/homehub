package com.homehub.bootstrap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class InfraBootstrapRunnerTest {

    @Test
    void ensuresListingsTable() {
        var table = mock(ListingsTableBootstrap.class);

        new InfraBootstrapRunner(table).run(null);

        verify(table).ensureTable();
    }
}
