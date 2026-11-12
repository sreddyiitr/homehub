package com.homehub.adapter.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.homehub.TestListings;
import com.homehub.config.HomeHubResources;
import com.homehub.domain.Listing;
import com.homehub.domain.ListingSearch;
import com.homehub.domain.ListingStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

class DynamoDbListingRepositoryTest {

    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final DynamoDbListingRepository repo = new DynamoDbListingRepository(dynamo);

    @Test
    void itemRoundTrip() {
        Listing l = TestListings.listing("LST-0001", ListingStatus.ACTIVE);
        assertThat(DynamoDbListingRepository.fromItem(DynamoDbListingRepository.toItem(l))).isEqualTo(l);
    }

    @Test
    void itemRoundTripWithoutDescription() {
        Listing base = TestListings.listing("LST-0001", ListingStatus.ACTIVE);
        Listing l = new Listing(base.listingId(), base.agentId(), base.status(), base.address(), base.city(),
                base.state(), base.zip(), base.price(), base.beds(), base.baths(), base.sqft(), null,
                base.version(), base.createdAt(), base.updatedAt());

        var item = DynamoDbListingRepository.toItem(l);

        assertThat(item).doesNotContainKey("description");
        assertThat(DynamoDbListingRepository.fromItem(item)).isEqualTo(l);
    }

    @ParameterizedTest
    @EnumSource(value = ListingStatus.class, names = {"ACTIVE", "UNDER_OFFER"})
    @DisplayName("D-2 searchable listings carry the sparse GSI key, normalized")
    void searchableHasSearchCity(ListingStatus status) {
        var item = DynamoDbListingRepository.toItem(TestListings.listing("LST-0001", status));
        assertThat(item.get("searchCity").s()).isEqualTo("seattle");
    }

    @ParameterizedTest
    @EnumSource(value = ListingStatus.class, names = {"DRAFT", "SOLD"})
    @DisplayName("R-SRCH-01.3 DRAFT/SOLD omit searchCity so they never enter the GSI")
    void unsearchableOmitsSearchCity(ListingStatus status) {
        assertThat(DynamoDbListingRepository.toItem(TestListings.listing("LST-0001", status)))
                .doesNotContainKey("searchCity");
    }

    @Test
    void findByIdPresentAndAbsent() {
        var item = DynamoDbListingRepository.toItem(TestListings.listing("LST-0001", ListingStatus.ACTIVE));
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build())
                .thenReturn(GetItemResponse.builder().build());

        assertThat(repo.findById("LST-0001")).map(Listing::listingId).contains("LST-0001");
        assertThat(repo.findById("LST-9999")).isEmpty();

        var captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo, org.mockito.Mockito.times(2)).getItem(captor.capture());
        assertThat(captor.getAllValues().get(0).tableName()).isEqualTo(HomeHubResources.LISTINGS_TABLE);
        assertThat(captor.getAllValues().get(0).key()).containsEntry("listingId", AttributeValue.fromS("LST-0001"));
    }

    @Test
    void putWritesToListingsTable() {
        repo.put(TestListings.listing("LST-0001", ListingStatus.ACTIVE));

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo(HomeHubResources.LISTINGS_TABLE);
        assertThat(captor.getValue().item()).containsKey("searchCity");
    }

    private QueryRequest searchAndCapture(ListingSearch search, QueryResponse response) {
        when(dynamo.query(any(QueryRequest.class))).thenReturn(response);
        repo.search(search);
        var captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(captor.capture());
        // R-SRCH-02: the ONLY call is a Query — never a Scan.
        verifyNoMoreInteractions(dynamo);
        return captor.getValue();
    }

    @Test
    @DisplayName("R-SRCH-01.2 price range goes in the KEY condition on the city-price GSI")
    void searchWithPriceRange() {
        var q = searchAndCapture(new ListingSearch(" Seattle ", 100L, 900L, null, 20, null), QueryResponse.builder().build());

        assertThat(q.indexName()).isEqualTo(HomeHubResources.LISTINGS_CITY_PRICE_INDEX);
        assertThat(q.keyConditionExpression()).isEqualTo("#city = :city AND #price BETWEEN :minPrice AND :maxPrice");
        assertThat(q.expressionAttributeValues())
                .containsEntry(":city", AttributeValue.fromS("seattle"))
                .containsEntry(":minPrice", AttributeValue.fromN("100"))
                .containsEntry(":maxPrice", AttributeValue.fromN("900"));
        assertThat(q.filterExpression()).isNull();
        assertThat(q.scanIndexForward()).isTrue();
        assertThat(q.limit()).isEqualTo(20);
        assertThat(q.hasExclusiveStartKey()).isFalse();
    }

    @Test
    void searchWithMinPriceOnly() {
        var q = searchAndCapture(new ListingSearch("Seattle", 100L, null, null, 20, null), QueryResponse.builder().build());
        assertThat(q.keyConditionExpression()).isEqualTo("#city = :city AND #price >= :minPrice");
    }

    @Test
    void searchWithMaxPriceOnly() {
        var q = searchAndCapture(new ListingSearch("Seattle", null, 900L, null, 20, null), QueryResponse.builder().build());
        assertThat(q.keyConditionExpression()).isEqualTo("#city = :city AND #price <= :maxPrice");
    }

    @Test
    @DisplayName("minBeds is a filter expression (not part of the key)")
    void searchWithMinBedsOnly() {
        var q = searchAndCapture(new ListingSearch("Seattle", null, null, 3, 20, null), QueryResponse.builder().build());

        assertThat(q.keyConditionExpression()).isEqualTo("#city = :city");
        assertThat(q.filterExpression()).isEqualTo("#beds >= :minBeds");
        assertThat(q.expressionAttributeNames()).containsEntry("#beds", "beds").doesNotContainKey("#price");
        assertThat(q.expressionAttributeValues()).containsEntry(":minBeds", AttributeValue.fromN("3"));
    }

    @Test
    @DisplayName("R-SRCH-01.4 nextToken round-trips through LastEvaluatedKey / ExclusiveStartKey")
    void searchPagination() {
        Map<String, AttributeValue> lek = Map.of(
                "listingId", AttributeValue.fromS("LST-0001"),
                "searchCity", AttributeValue.fromS("seattle"),
                "price", AttributeValue.fromN("850000"));
        var item = DynamoDbListingRepository.toItem(TestListings.listing("LST-0001", ListingStatus.ACTIVE));
        when(dynamo.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(item).lastEvaluatedKey(lek).build());

        var page = repo.search(new ListingSearch("Seattle", null, null, null, 1, null));
        assertThat(page.items()).extracting(Listing::listingId).containsExactly("LST-0001");
        assertThat(page.nextToken()).isNotBlank();

        repo.search(new ListingSearch("Seattle", null, null, null, 1, page.nextToken()));
        var captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo, org.mockito.Mockito.times(2)).query(captor.capture());
        assertThat(captor.getAllValues().get(1).exclusiveStartKey()).isEqualTo(lek);
    }

    @Test
    void lastPageHasNoNextToken() {
        when(dynamo.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().build());
        assertThat(repo.search(new ListingSearch("Seattle", null, null, null, 1, null)).nextToken()).isNull();
    }
}
