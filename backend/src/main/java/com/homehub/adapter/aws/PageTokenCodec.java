package com.homehub.adapter.aws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homehub.domain.InvalidRequestException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Turns DynamoDB's LastEvaluatedKey into an opaque {@code nextToken} and back.
 *
 * <p>DynamoDB paginates by handing back the key of the last item it read; the
 * client passes it as ExclusiveStartKey to continue. We base64 it so the API
 * doesn't expose DynamoDB types. On real AWS you'd also sign or encrypt it —
 * as-is it reveals key structure and a client could forge a start key.
 */
final class PageTokenCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Map<String, String>>> SHAPE = new TypeReference<>() {
    };

    private PageTokenCodec() {
    }

    static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        Map<String, Map<String, String>> plain = new LinkedHashMap<>();
        lastEvaluatedKey.forEach((name, value) -> plain.put(name,
                value.n() != null ? Map.of("N", value.n()) : Map.of("S", value.s())));
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(plain));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode page token", e);
        }
    }

    static Map<String, AttributeValue> decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Map<String, Map<String, String>> plain = JSON.readValue(Base64.getUrlDecoder().decode(token), SHAPE);
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            plain.forEach((name, typed) -> {
                if (typed.containsKey("N")) {
                    key.put(name, AttributeValue.fromN(typed.get("N")));
                } else if (typed.containsKey("S")) {
                    key.put(name, AttributeValue.fromS(typed.get("S")));
                } else {
                    throw new IllegalArgumentException("bad attribute type");
                }
            });
            return key;
        } catch (Exception e) {
            throw InvalidRequestException.of("nextToken", "is invalid");
        }
    }
}
