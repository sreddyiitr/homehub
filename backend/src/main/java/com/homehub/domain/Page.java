package com.homehub.domain;

import java.util.List;

/** One page of results; {@code nextToken} is null on the last page. */
public record Page<T>(List<T> items, String nextToken) {
}
