package com.comet.opik.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * 一行记录被写入删除事件桥接表的原因，存储为 {@code deletion_reason} 列。
 */
@Getter
@RequiredArgsConstructor
public enum DeletionReason {

    USER_REQUEST("user_request"),
    CASCADE("cascade");

    private final String value;

    public static Optional<DeletionReason> fromString(String value) {
        return Arrays.stream(values())
                .filter(reason -> reason.value.equalsIgnoreCase(value))
                .findFirst();
    }

    public static DeletionReason fromStringOrThrow(String value) {
        return fromString(value)
                .orElseThrow(() -> new IllegalStateException("Unknown deletion reason: '%s'".formatted(value)));
    }
}
