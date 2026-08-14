package com.comet.opik.api;

import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

/**
 * 用于将 Instant 时间边界转换为 UUIDv7 边界，以实现高效 BETWEEN 查询的映射器。
 *
 * UUIDv7 将时间戳编码在前 48 位中，从而允许按时间进行字典序排序。
 * 该映射器创建 UUID 边界，以确保基于时间的过滤具有正确的 BETWEEN 查询语义。
 *
 * 实现说明：
 * 我们创建所有随机位都设置为 0（下界）或 1（上界）的 UUID，以保证
 * 给定时间戳的最小和最大 UUID。这确保了所有时间戳在查询范围内的 UUID 都包含在 BETWEEN 子句中。
 */
@Singleton
@Slf4j
public class InstantToUUIDMapper {

    /**
     * 从时间戳生成用于 BETWEEN 查询的 UUIDv7 下界。
     * 通过将所有随机位设置为 0，创建该时间戳下字典序最小的 UUID。
     *
     * UUIDv7 结构（RFC 9562）：
     * - 第 0-47 位：Unix 时间戳，单位为毫秒（48 位）
     * - 第 48-51 位：版本 = 0111 (7)
     * - 第 52-63 位：亚毫秒精度或计数器（12 位）- 下界设置为 0
     * - 第 64-65 位：变体 = 10
     * - 第 66-127 位：随机（62 位）- 下界设置为 0
     *
     * @param timestamp 时间点
     * @return 下界 UUIDv7（该时间戳处的最小 UUID）
     */
    public UUID toLowerBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        // UUIDv7 的 48 位时间戳是无符号的自纪元以来毫秒数；负值（1970 年之前）会溢出
        // 并回绕成一个排序在所有真实 id 之上的 UUID，因此将其钳制到纪元下限。
        long epochMilli = Math.max(0L, timestamp.toEpochMilli());

        // 最高有效位：[时间戳: 48 位][版本: 4 位][随机: 12 位]
        // 对于下界，将 12 个随机位设置为 0
        long msb = (epochMilli << 16) // 将时间戳左移到高 48 位
                | (0x7000L); // 将版本设置为 7（第 48-51 位），其余位为 0

        // 最低有效位：[变体: 2 位][随机: 62 位]
        // 对于下界，将所有 62 个随机位设置为 0
        long lsb = 0x8000000000000000L; // 将变体设置为 10（第 64-65 位），其余位为 0

        return new UUID(msb, lsb);
    }

    /**
     * 从时间戳生成用于 BETWEEN 查询的 UUIDv7 上界。
     * 通过将所有随机位设置为 1，创建该时间戳下字典序最大的 UUID。
     *
     * 这确保了 BETWEEN 包含在结束时间戳毫秒内创建的所有 UUID。
     * 例如，如果查询 10:00:00.000 到 10:00:01.000 之间的 trace：
     * - toLowerBound(10:00:00.000) 给出 10:00:00.000 处的最小 UUID（随机位 = 0）
     * - toUpperBound(10:00:01.000) 给出 10:00:01.000 处的最大 UUID（随机位 = 1）
     * - BETWEEN x AND y 包含从 10:00:00.000 到 10:00:01.000 的所有 UUID
     *
     * UUIDv7 结构（RFC 9562）：
     * - 第 0-47 位：Unix 时间戳，单位为毫秒（48 位）
     * - 第 48-51 位：版本 = 0111 (7)
     * - 第 52-63 位：亚毫秒精度或计数器（12 位）- 上界设置为 1
     * - 第 64-65 位：变体 = 10
     * - 第 66-127 位：随机（62 位）- 上界设置为 1
     *
     * @param timestamp 时间点
     * @return 上界 UUIDv7（该时间戳处的最大 UUID），如果 timestamp 为 null 则返回 null
     */
    public UUID toUpperBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        // 钳制到纪元下限：负值（1970 年之前）会溢出无符号的 48 位时间戳。
        long epochMilli = Math.max(0L, timestamp.toEpochMilli());

        // 最高有效位：[时间戳: 48 位][版本: 4 位][随机: 12 位]
        // 对于上界，将 12 个随机位设置为 1
        long msb = (epochMilli << 16) // 将时间戳左移到高 48 位
                | (0x7FFFL); // 将版本设置为 7（第 48-51 位），其余 12 位为 1

        // 最低有效位：[变体: 2 位][随机: 62 位]
        // 对于上界，将所有 62 个随机位设置为 1
        // 以变体 10（0x8000000000000000L）开始，然后与最大随机位（0x3FFFFFFFFFFFFFFFL）进行 OR 运算
        // 0x3FFFFFFFFFFFFFFFL 恰好有 62 位设置为 1（二进制：0011111111...1111）
        long lsb = 0x8000000000000000L | 0x3FFFFFFFFFFFFFFFL; // 变体 10 + 所有 62 个随机位都设置为 1

        return new UUID(msb, lsb);
    }
}
