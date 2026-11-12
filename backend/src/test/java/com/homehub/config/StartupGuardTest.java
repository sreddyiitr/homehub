package com.homehub.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StartupGuardTest {

    private static HomeHubProperties props(boolean bootstrap, boolean seed, String endpoint) {
        return new HomeHubProperties(bootstrap, seed, "../test/data",
                new HomeHubProperties.Aws(endpoint, "us-west-2", null),
                new HomeHubProperties.Cors(List.of()));
    }

    @Test
    @DisplayName("R-CFG-04 local profile without AWS_ENDPOINT_URL fails fast")
    void localWithoutEndpoint() {
        assertThatThrownBy(() -> StartupGuard.check(List.of("local"), props(true, true, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_ENDPOINT_URL");
        assertThatThrownBy(() -> StartupGuard.check(List.of("local"), props(true, true, null)))
                .hasMessageContaining("AWS_ENDPOINT_URL");
    }

    @Test
    @DisplayName("R-CFG-04 local profile refuses a non-loopback endpoint")
    void localWithRemoteEndpoint() {
        assertThatThrownBy(() -> StartupGuard.check(List.of("local"),
                props(true, true, "https://dynamodb.us-west-2.amazonaws.com")))
                .hasMessageContaining("loopback");
    }

    @Test
    void localWithLoopbackEndpointPasses() {
        assertThatCode(() -> StartupGuard.check(List.of("local"), props(true, true, "https://127.0.0.1:9297")))
                .doesNotThrowAnyException();
        assertThatCode(() -> StartupGuard.check(List.of("local"), props(true, true, "http://localhost:4566")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R-CFG-01.3 aws profile rejects bootstrap or seeding")
    void awsRejectsBootstrapAndSeed() {
        assertThatThrownBy(() -> StartupGuard.check(List.of("aws"), props(true, false, null)))
                .hasMessageContaining("must be false");
        assertThatThrownBy(() -> StartupGuard.check(List.of("aws"), props(false, true, null)))
                .hasMessageContaining("must be false");
        assertThatCode(() -> StartupGuard.check(List.of("aws"), props(false, false, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void localAndAwsAreMutuallyExclusive() {
        assertThatThrownBy(() -> StartupGuard.check(List.of("local", "aws"), props(false, false, "https://127.0.0.1:1")))
                .hasMessageContaining("mutually exclusive");
    }
}
