package com.homehub.bootstrap;

import static com.homehub.config.HomeHubResources.LISTINGS_AGENT_INDEX;
import static com.homehub.config.HomeHubResources.LISTINGS_CITY_PRICE_INDEX;
import static com.homehub.config.HomeHubResources.LISTINGS_TABLE;

import com.homehub.telemetry.HomeHubTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Creates the {@code Listings} table and its GSIs if missing (design §3.1).
 *
 * <p>Idempotent: DescribeTable first, CreateTable only on ResourceNotFound.
 * CreateTable is asynchronous (the table starts in CREATING), so we block on
 * the SDK waiter until it is ACTIVE before anyone writes to it.
 *
 * <p>PAY_PER_REQUEST (on-demand) means no capacity planning. On real AWS
 * that's the sensible default for spiky/low traffic; provisioned capacity is
 * cheaper only for steady, predictable load.
 */
@Component
public class ListingsTableBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ListingsTableBootstrap.class);

    private final DynamoDbClient dynamo;

    public ListingsTableBootstrap(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    /** @return true if the table was created, false if it already existed. */
    @WithSpan("bootstrap-dynamodb-table")
    public boolean ensureTable() {
        boolean created = EmulatorCalls.call("dynamodb", "DescribeTable/CreateTable", () -> {
            if (exists()) {
                log.info("DynamoDB table {} exists, skipping", LISTINGS_TABLE);
                return false;
            }
            try {
                dynamo.createTable(createRequest());
            } catch (ResourceInUseException raced) {
                // Another process created it between Describe and Create — fine.
            }
            dynamo.waiter().waitUntilTableExists(b -> b.tableName(LISTINGS_TABLE));
            log.info("Created DynamoDB table {} with GSIs {}, {}",
                    LISTINGS_TABLE, LISTINGS_CITY_PRICE_INDEX, LISTINGS_AGENT_INDEX);
            return true;
        });
        // Table name etc. come from the AWS SDK auto-instrumentation spans — not duplicated here.
        Span.current().setAttribute(HomeHubTelemetry.RESOURCE_CREATED, created);
        return created;
    }

    private boolean exists() {
        try {
            dynamo.describeTable(b -> b.tableName(LISTINGS_TABLE));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    static CreateTableRequest createRequest() {
        return CreateTableRequest.builder()
                .tableName(LISTINGS_TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                // Only KEY attributes (table + GSI keys) are declared; DynamoDB is schemaless otherwise.
                .attributeDefinitions(
                        attr("listingId", ScalarAttributeType.S),
                        attr("searchCity", ScalarAttributeType.S),
                        attr("price", ScalarAttributeType.N),
                        attr("agentId", ScalarAttributeType.S),
                        attr("createdAt", ScalarAttributeType.S))
                .keySchema(key("listingId", KeyType.HASH))
                .globalSecondaryIndexes(
                        gsi(LISTINGS_CITY_PRICE_INDEX, "searchCity", "price"),
                        gsi(LISTINGS_AGENT_INDEX, "agentId", "createdAt"))
                .build();
    }

    private static AttributeDefinition attr(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    /** Projection ALL: search returns full listings without a second GetItem per result. */
    private static GlobalSecondaryIndex gsi(String name, String pk, String sk) {
        return GlobalSecondaryIndex.builder()
                .indexName(name)
                .keySchema(key(pk, KeyType.HASH), key(sk, KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();
    }
}
