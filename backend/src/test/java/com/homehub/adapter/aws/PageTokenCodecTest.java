package com.homehub.adapter.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homehub.domain.InvalidRequestException;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class PageTokenCodecTest {

    @Test
    void roundTripsStringAndNumberKeys() {
        Map<String, AttributeValue> key = Map.of("a", AttributeValue.fromS("x"), "b", AttributeValue.fromN("42"));
        String token = PageTokenCodec.encode(key);

        assertThat(token).doesNotContain("=", "+", "/"); // URL-safe, unpadded
        assertThat(PageTokenCodec.decode(token)).isEqualTo(key);
    }

    @Test
    void emptyMeansNoToken() {
        assertThat(PageTokenCodec.encode(null)).isNull();
        assertThat(PageTokenCodec.encode(Map.of())).isNull();
        assertThat(PageTokenCodec.decode(null)).isNull();
        assertThat(PageTokenCodec.decode(" ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zzz", "!!!", "bm90IGpzb24"}) // bad JSON, not base64, base64 of "not json"
    void garbageIsA400(String token) {
        assertThatThrownBy(() -> PageTokenCodec.decode(token))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.fieldErrors()).containsEntry("nextToken", "is invalid"));
    }

    @Test
    void unknownAttributeTypeIsA400() {
        String token = Base64.getUrlEncoder().encodeToString("{\"a\":{\"BOOL\":\"true\"}}".getBytes());
        assertThatThrownBy(() -> PageTokenCodec.decode(token)).isInstanceOf(InvalidRequestException.class);
    }
}
