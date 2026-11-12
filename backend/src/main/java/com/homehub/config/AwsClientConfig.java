package com.homehub.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * AWS SDK v2 clients. Everything environment-specific comes from config:
 * <ul>
 *   <li>endpoint: {@code AWS_ENDPOINT_URL} under 'local'; none under 'aws'</li>
 *   <li>credentials: NOT configured here. The SDK's default chain reads
 *       {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} (exported from
 *       the emulator's credentials file by scripts/emulator-env.sh), and on
 *       real AWS it would use the instance/task role.</li>
 * </ul>
 */
@Configuration
public class AwsClientConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(HomeHubProperties props, Environment env) {
        // 'local' is the DEFAULT profile, which getActiveProfiles() doesn't report.
        String[] profiles = env.getActiveProfiles().length > 0 ? env.getActiveProfiles() : env.getDefaultProfiles();
        StartupGuard.check(Arrays.asList(profiles), props);

        UrlConnectionHttpClient.Builder http = UrlConnectionHttpClient.builder();
        if (props.aws().hasCaBundle()) {
            Path ca = Path.of(props.aws().caBundle());
            http.tlsTrustManagersProvider(() -> PemTrustManagers.fromPem(ca));
        }

        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(props.aws().region()))
                .httpClientBuilder(http);
        if (props.aws().hasEndpoint()) {
            builder.endpointOverride(URI.create(props.aws().endpoint()));
        }
        return builder.build();
    }
}
