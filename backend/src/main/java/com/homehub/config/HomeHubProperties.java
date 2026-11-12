package com.homehub.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed view of the {@code homehub.*} properties (see application.yml). */
@ConfigurationProperties("homehub")
public record HomeHubProperties(
        @DefaultValue("false") boolean bootstrapInfra,
        @DefaultValue("false") boolean seedData,
        @DefaultValue("../test/data") String seedPath,
        @DefaultValue Aws aws,
        @DefaultValue Cors cors) {

    /**
     * @param endpoint  endpoint override; empty means "use the real AWS endpoint"
     * @param region    AWS region
     * @param caBundle  PEM file of an extra CA to trust (the emulator's per-run CA)
     */
    public record Aws(String endpoint, @DefaultValue("us-west-2") String region, String caBundle) {
        public boolean hasEndpoint() {
            return endpoint != null && !endpoint.isBlank();
        }

        public boolean hasCaBundle() {
            return caBundle != null && !caBundle.isBlank();
        }
    }

    public record Cors(@DefaultValue List<String> allowedOrigins) {
    }
}
