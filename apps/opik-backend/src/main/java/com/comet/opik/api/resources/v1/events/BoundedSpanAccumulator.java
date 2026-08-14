package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.utils.JsonUtils;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AgenticScoringService#preloadThreadSpansBounded} 背后的可变累加器：在跟踪跨度近似序列化大小的
 * 同时缓冲跨度，并在运行总量越过上限时报告。
 *
 * <p>未同步，仅因为它被限定在单个响应式订阅中、且仅从该管道的串行信号中被触碰才是安全的。
 * Reactive Streams 契约以先后顺序（其间具有 happens-before 排序）交付 {@code onNext} 和终止信号，
 * 因此字段变更无需加锁即可见。它不得跨订阅或线程共享。
 */
@RequiredArgsConstructor
final class BoundedSpanAccumulator {

    private final long maxPreloadBytes;
    private final List<Span> spans = new ArrayList<>();
    private long approxBytes;
    private boolean overflowed;

    /**
     * 除非上限已被（或现在被）越过，否则缓冲 {@code span}。
     *
     * @return 一旦运行大小越过上限即返回 {@code true}，示意调用方停止
     */
    boolean addAndCheckOverflow(@NonNull Span span) {
        if (overflowed) {
            return true;
        }
        approxBytes += approxSpanBytes(span);
        if (approxBytes > maxPreloadBytes) {
            overflowed = true;
            spans.clear(); // 越过上限：丢弃缓冲区；工具路径按需逐追踪下钻
            return true;
        }
        spans.add(span);
        return false;
    }

    ThreadSpanPreload toPreload() {
        return ThreadSpanPreload.builder()
                .spans(overflowed ? List.of() : List.copyOf(spans))
                .approxBytes(approxBytes)
                .overflowed(overflowed)
                .build();
    }

    private long approxSpanBytes(Span span) {
        return JsonUtils.getSerializedLengthInBytes(span.input())
                + JsonUtils.getSerializedLengthInBytes(span.output())
                + JsonUtils.getSerializedLengthInBytes(span.metadata());
    }
}
