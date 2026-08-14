package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * {@link AgenticScoringService#preloadThreadSpansBounded} 的结果。当线程在上限之内时（内联/增强路径），
 * {@code spans} 保存缓冲的跨度；当 {@code overflowed} 时为 empty，因为 agentic 工具路径按需逐追踪下钻
 * 且无需缓冲区。{@code approxBytes} 是停止前看到的累计近似序列化大小。
 */
@Builder(toBuilder = true)
public record ThreadSpanPreload(@NonNull List<Span> spans, long approxBytes, boolean overflowed) {
}
