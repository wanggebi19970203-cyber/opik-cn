package com.comet.opik.utils;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

/**
 * API 契约（Java {@code null} / {@code Optional<T>} ↔ JSON {@code null}）与支撑
 * 不可空分析列的哨兵值之间的单一转换点。
 *
 * <p>{@code Nullable(...)} 携带逐行的 null 掩码文件，无法支撑表索引，因此它从
 * 高流量的 {@code traces}/{@code spans} 列中被丢弃，改为每种类型一个无碰撞的哨兵：</p>
 * <ul>
 *     <li>{@code DateTime64} → {@link Instant#EPOCH}（{@code 1970-01-01 00:00:00}）；没有真实事件时间戳
 *     会与 epoch 碰撞，因此它读作“尚未结束” / “从未评分”。</li>
 *     <li>{@code Float64} → {@link Double#NaN}；{@code 0} 是合法测量值（瞬时首 token、
 *     亚毫秒 span），因此 {@code NaN} 是唯一无法与真实数据碰撞的值。</li>
 *     <li>{@code FixedString(36)} → 空字符串；ClickHouse 还会从 LEFT JOIN 中暴露全空字节形式
 *     （{@link ValidationUtils#CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE}），因此两者都读作缺失。</li>
 * </ul>
 *
 * <p>把 {@code sentinel ↔ null} 映射集中在一处，与代码库中 {@code ''}
 * 默认字符串的既有惯例一致，并保证每个读取或写入这些列之一的 DAO
 * 继承相同的契约，而不是各自重新推导。</p>
 */
@UtilityClass
public class SentinelTranslation {

    /** 不可空 {@code DateTime64(9)} 列的哨兵（{@code DEFAULT toDateTime64('1970-01-01 00:00:00', 9)}），匹配 {@code precision-9} SQL 片段和当前的 {@code end_time} 列。 */
    public static final Instant EPOCH_SENTINEL = Instant.EPOCH;

    /** 不可空 {@code FixedString(36)} 列的哨兵（{@code DEFAULT ''}）。 */
    public static final String EMPTY_UUID_SENTINEL = "";

    // 出站：ClickHouse 哨兵 → API null。

    /**
     * @return 当值缺失时返回 {@code null} —— {@code null} 或 epoch 哨兵 —— 否则返回该值本身。
     */
    public static Instant epochToNull(Instant value) {
        return Optional.ofNullable(value)
                .filter(present -> !EPOCH_SENTINEL.equals(present))
                .orElse(null);
    }

    /**
     * @return 当值缺失时返回 {@code null} —— {@code null} 或 {@code NaN} —— 否则返回该值本身。
     * 无穷值原样透传：它们表示数据缺陷，而不是缺失值哨兵。
     */
    public static Double nanToNull(Double value) {
        return Optional.ofNullable(value)
                .filter(present -> !present.isNaN())
                .orElse(null);
    }

    /**
     * @return 当值缺失时返回 {@code null} —— {@code null}、空、或全空字节的
     * {@code FixedString(36)} 形式 —— 否则返回该值本身。
     */
    public static String emptyUuidToNull(String value) {
        return Optional.ofNullable(value)
                .filter(StringUtils::isNotBlank)
                .filter(present -> !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(present))
                .orElse(null);
    }

    /**
     * 与 {@link #emptyUuidToNull(String)} 相同，但解析为 {@link UUID}：这是读取路径为持有 UUID 或
     * 空哨兵的 {@code FixedString(36)} 列所需的唯一调用。先经过哨兵检查正是让它
     * 安全的原因 —— 驱动把哨兵暴露为 36 个 NUL 字符，它们不是空白，因此直接解析
     * 原始值（比如在 {@code isBlank} 守卫之后）会抛出异常，而不是读作缺失。
     *
     * @return 当值缺失时返回 {@code null}，否则返回解析后的 UUID。
     * @throws IllegalArgumentException 当值存在但不是 UUID 时。
     */
    public static UUID emptyUuidToNullableUuid(String value) {
        return Optional.ofNullable(emptyUuidToNull(value))
                .map(UUID::fromString)
                .orElse(null);
    }

    // 入站：API null → ClickHouse 哨兵。

    public static Instant nullToEpoch(Instant value) {
        return Optional.ofNullable(value).orElse(EPOCH_SENTINEL);
    }

    public static double nullToNaN(Double value) {
        return Optional.ofNullable(value).orElse(Double.NaN);
    }

    /**
     * @return 当值缺失时返回空哨兵 —— {@code null}、空或空白 —— 否则返回该值本身。
     * 把空白一并折叠（而不仅仅是 {@code null}），是为了与 {@link #emptyUuidToNull} 一致，并阻止一个
     * 空白的入站值被存储为部分 NUL 的 {@code FixedString(36)}，后者读回来既不是哨兵也不是 UUID。
     */
    public static String nullToEmptyUuid(String value) {
        return Optional.ofNullable(value)
                .filter(StringUtils::isNotBlank)
                .orElse(EMPTY_UUID_SENTINEL);
    }

    /**
     * 与 {@link #nullToEmptyUuid(String)} 相同，但针对 {@link UUID}，因此绑定 {@code FixedString(36)} 列的
     * 写入路径无需在 {@link UUID#toString()} 周围写明 null 检查。
     */
    public static String nullToEmptyUuid(UUID value) {
        return Optional.ofNullable(value)
                .map(UUID::toString)
                .orElse(EMPTY_UUID_SENTINEL);
    }
}
