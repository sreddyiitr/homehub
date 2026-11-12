package com.homehub.adapter.aws;

import static com.homehub.config.HomeHubResources.LISTINGS_CITY_PRICE_INDEX;
import static com.homehub.config.HomeHubResources.LISTINGS_TABLE;

import com.homehub.domain.Listing;
import com.homehub.domain.ListingSearch;
import com.homehub.domain.ListingStatus;
import com.homehub.domain.Page;
import com.homehub.port.ListingRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * DynamoDB implementation of {@link ListingRepository}, using the low-level
 * client so the attribute names in design §3.1 are visible in code.
 *
 * <p><b>Sparse index pattern:</b> {@code searchCity} is written only when the
 * listing's status is searchable. DynamoDB copies an item into a GSI only if
 * the item has the GSI's key attributes, so DRAFT and SOLD listings simply
 * don't exist in {@code city-price-index}. The search can never return them,
 * and it reads (and pays for) nothing it has to filter out.
 */
@Repository
public class DynamoDbListingRepository implements ListingRepository {

    static final String SEARCH_CITY = "searchCity";

    private final DynamoDbClient dynamo;

    public DynamoDbListingRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    /** Normalized GSI partition value, so "Seattle" and "seattle" hit the same partition. */
    static String normalizeCity(String city) {
        return city.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public Optional<Listing> findById(String listingId) {
        var item = dynamo.getItem(GetItemRequest.builder()
                .tableName(LISTINGS_TABLE)
                .key(Map.of("listingId", AttributeValue.fromS(listingId)))
                .build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }

    @Override
    public Page<Listing> search(ListingSearch s) {
        Map<String, String> names = new HashMap<>(Map.of("#city", SEARCH_CITY));
        Map<String, AttributeValue> values = new HashMap<>(Map.of(":city", AttributeValue.fromS(normalizeCity(s.city()))));

        // Price is the GSI sort key, so the range goes in the KEY condition:
        // DynamoDB reads only the matching slice of the partition.
        String keyCondition = "#city = :city";
        if (s.minPrice() != null || s.maxPrice() != null) {
            names.put("#price", "price");
        }
        if (s.minPrice() != null && s.maxPrice() != null) {
            keyCondition += " AND #price BETWEEN :minPrice AND :maxPrice";
            values.put(":minPrice", num(s.minPrice()));
            values.put(":maxPrice", num(s.maxPrice()));
        } else if (s.minPrice() != null) {
            keyCondition += " AND #price >= :minPrice";
            values.put(":minPrice", num(s.minPrice()));
        } else if (s.maxPrice() != null) {
            keyCondition += " AND #price <= :maxPrice";
            values.put(":maxPrice", num(s.maxPrice()));
        }

        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(LISTINGS_TABLE)
                .indexName(LISTINGS_CITY_PRICE_INDEX)
                .keyConditionExpression(keyCondition)
                .scanIndexForward(true) // price ascending
                .limit(s.limit())
                .exclusiveStartKey(PageTokenCodec.decode(s.nextToken()));

        // beds isn't part of the key, so it's a FILTER: applied after items are
        // read. Note Limit counts items READ, not items returned, so a filtered
        // page can be shorter than `limit` while nextToken is still present.
        if (s.minBeds() != null) {
            names.put("#beds", "beds");
            values.put(":minBeds", num(s.minBeds()));
            query.filterExpression("#beds >= :minBeds");
        }

        QueryResponse response = dynamo.query(query
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build());

        return new Page<>(
                response.items().stream().map(DynamoDbListingRepository::fromItem).toList(),
                response.hasLastEvaluatedKey() ? PageTokenCodec.encode(response.lastEvaluatedKey()) : null);
    }

    @Override
    public void put(Listing listing) {
        dynamo.putItem(PutItemRequest.builder().tableName(LISTINGS_TABLE).item(toItem(listing)).build());
    }

    static Map<String, AttributeValue> toItem(Listing l) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("listingId", AttributeValue.fromS(l.listingId()));
        item.put("agentId", AttributeValue.fromS(l.agentId()));
        item.put("status", AttributeValue.fromS(l.status().name()));
        item.put("address", AttributeValue.fromS(l.address()));
        item.put("city", AttributeValue.fromS(l.city()));
        item.put("state", AttributeValue.fromS(l.state()));
        item.put("zip", AttributeValue.fromS(l.zip()));
        item.put("price", num(l.price()));
        item.put("beds", num(l.beds()));
        item.put("baths", AttributeValue.fromN(Double.toString(l.baths())));
        item.put("sqft", num(l.sqft()));
        if (l.description() != null) {
            item.put("description", AttributeValue.fromS(l.description()));
        }
        item.put("version", num(l.version()));
        item.put("createdAt", AttributeValue.fromS(l.createdAt().toString()));
        item.put("updatedAt", AttributeValue.fromS(l.updatedAt().toString()));
        if (l.status().isSearchable()) {
            item.put(SEARCH_CITY, AttributeValue.fromS(normalizeCity(l.city())));
        }
        return item;
    }

    static Listing fromItem(Map<String, AttributeValue> i) {
        return new Listing(
                i.get("listingId").s(),
                i.get("agentId").s(),
                ListingStatus.valueOf(i.get("status").s()),
                i.get("address").s(),
                i.get("city").s(),
                i.get("state").s(),
                i.get("zip").s(),
                Long.parseLong(i.get("price").n()),
                Integer.parseInt(i.get("beds").n()),
                Double.parseDouble(i.get("baths").n()),
                Integer.parseInt(i.get("sqft").n()),
                i.containsKey("description") ? i.get("description").s() : null,
                Long.parseLong(i.get("version").n()),
                Instant.parse(i.get("createdAt").s()),
                Instant.parse(i.get("updatedAt").s()));
    }

    private static AttributeValue num(long n) {
        return AttributeValue.fromN(Long.toString(n));
    }
}
