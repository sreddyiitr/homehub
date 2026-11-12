package com.homehub.config;

import java.net.URI;
import java.util.Collection;
import java.util.Set;

/**
 * Fail-fast checks that stop the app from ever doing local-only things
 * against real AWS (R-CFG-01.3, R-CFG-04). Pure function so it is trivially
 * unit-testable; {@link AwsClientConfig} calls it before building clients.
 */
public final class StartupGuard {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1", "[::1]");

    private StartupGuard() {
    }

    public static void check(Collection<String> activeProfiles, HomeHubProperties props) {
        boolean local = activeProfiles.contains("local");
        boolean aws = activeProfiles.contains("aws");

        if (local && aws) {
            throw new IllegalStateException("Profiles 'local' and 'aws' are mutually exclusive");
        }
        if (aws && (props.bootstrapInfra() || props.seedData())) {
            throw new IllegalStateException(
                    "homehub.bootstrap-infra and homehub.seed-data must be false under the 'aws' profile");
        }
        if (local) {
            if (!props.aws().hasEndpoint()) {
                throw new IllegalStateException(
                        "Profile 'local' requires AWS_ENDPOINT_URL (source scripts/emulator-env.sh). "
                                + "Refusing to fall back to real AWS.");
            }
            String host = URI.create(props.aws().endpoint()).getHost();
            if (host == null || !LOOPBACK_HOSTS.contains(host)) {
                throw new IllegalStateException(
                        "Profile 'local' endpoint must be a loopback address, got: " + host);
            }
        }
    }
}
