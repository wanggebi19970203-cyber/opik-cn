package com.comet.opik.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * 删除事件桥接行所来源的表，存储为 {@code source_table} 列。
 */
@Getter
@RequiredArgsConstructor
public enum SourceTable {

    TRACES("traces"),
    SPANS("spans");

    private final String value;

    public static Optional<SourceTable> fromString(String value) {
        return Arrays.stream(values())
                .filter(sourceTable -> sourceTable.value.equalsIgnoreCase(value))
                .findFirst();
    }

    public static SourceTable fromStringOrThrow(String value) {
        return fromString(value)
                .orElseThrow(() -> new IllegalStateException("Unknown source table: '%s'".formatted(value)));
    }
}
