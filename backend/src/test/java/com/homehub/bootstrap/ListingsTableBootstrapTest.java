package com.homehub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homehub.config.HomeHubResources;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

@SuppressWarnings("unchecked")
class ListingsTableBootstrapTest {

    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final DynamoDbWaiter waiter = mock(DynamoDbWaiter.class);
    private final ListingsTableBootstrap bootstrap = new ListingsTableBootstrap(dynamo);

    @BeforeEach
    void waiterStub() {
        when(dynamo.waiter()).thenReturn(waiter);
    }

    @Test
    @DisplayName("R-INF-01.1 existing table: nothing is created")
    void skipsExisting() {
        when(dynamo.describeTable(any(Consumer.class))).thenReturn(DescribeTableResponse.builder().build());

        assertThat(bootstrap.ensureTable()).isFalse();
        verify(dynamo, never()).createTable(any(CreateTableRequest.class));
    }

    @Test
    @DisplayName("R-INF-01 missing table: created, then waits for ACTIVE")
    void createsMissing() {
        when(dynamo.describeTable(any(Consumer.class))).thenThrow(ResourceNotFoundException.builder().build());

        assertThat(bootstrap.ensureTable()).isTrue();
        verify(dynamo).createTable(ListingsTableBootstrap.createRequest());
        verify(waiter).waitUntilTableExists(any(Consumer.class));
    }

    @Test
    void toleratesConcurrentCreate() {
        when(dynamo.describeTable(any(Consumer.class))).thenThrow(ResourceNotFoundException.builder().build());
        when(dynamo.createTable(any(CreateTableRequest.class))).thenThrow(ResourceInUseException.builder().build());

        assertThat(bootstrap.ensureTable()).isTrue();
        verify(waiter).waitUntilTableExists(any(Consumer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {501, 503})
    @DisplayName("R-INF-03 emulator 'not implemented' (501, verified) / 503 fails loudly naming service and operation")
    void emulatorNotSupported(int status) {
        when(dynamo.describeTable(any(Consumer.class)))
                .thenThrow(AwsServiceException.builder().statusCode(status).message("nope").build());

        assertThatThrownBy(bootstrap::ensureTable)
                .isInstanceOf(EmulatorCalls.EmulatorUnsupportedOperationException.class)
                .hasMessageContaining("HTTP " + status)
                .hasMessageContaining("dynamodb:DescribeTable/CreateTable");
    }

    @Test
    void otherServiceErrorsPropagateUnchanged() {
        var boom = AwsServiceException.builder().statusCode(400).message("bad").build();
        when(dynamo.describeTable(any(Consumer.class))).thenThrow(boom);

        assertThatThrownBy(bootstrap::ensureTable).isSameAs(boom);
    }

    @Test
    void createRequestMatchesDesign() {
        CreateTableRequest req = ListingsTableBootstrap.createRequest();

        assertThat(req.tableName()).isEqualTo(HomeHubResources.LISTINGS_TABLE);
        assertThat(req.billingMode()).isEqualTo(BillingMode.PAY_PER_REQUEST);
        assertThat(req.globalSecondaryIndexes()).extracting(GlobalSecondaryIndex::indexName)
                .containsExactlyInAnyOrder(HomeHubResources.LISTINGS_CITY_PRICE_INDEX, HomeHubResources.LISTINGS_AGENT_INDEX);
        GlobalSecondaryIndex search = req.globalSecondaryIndexes().stream()
                .filter(g -> g.indexName().equals(HomeHubResources.LISTINGS_CITY_PRICE_INDEX)).findFirst().orElseThrow();
        assertThat(search.keySchema()).extracting(k -> k.attributeName()).containsExactly("searchCity", "price");
        assertThat(search.projection().projectionType()).isEqualTo(ProjectionType.ALL);
    }
}
