import { logger } from "@/utils/logger";

/**
 * 针对 span 和 trace 的每个对象载荷大小限制（与
 * Python SDK 保持一致，OPIK-7335）。
 *
 * `input`/`output` 字段非常大的 span 或 trace——例如将整个
 * 检索结果集以内联方式记录——会在后端膨胀成数 GB 的结构，
 * 并可能破坏数据摄取。此逻辑通过一个简单、可预测的两遍规则来施加每个对象的大小限制：
 *   1. 截断任何自身超过限制的字段（常见情况：一个
 *      巨大的字段；较小的同级字段保留）；
 *   2. 如果 input+output 合计仍超过限制，则同时截断剩余的
 *      可截断字段。
 *
 * `metadata` 有意地从不被截断（它保存了消费方依赖的小型结构化字段），
 * 并被排除在整个对象的度量之外，因此较大的
 * metadata 不会触发对 input/output 的截断。超大的字段会
 * 被替换为紧凑的标记；并记录一条警告。非变更式：返回应用了
 * 标记的浅拷贝。
 */

// 只有 input/output 可被截断；metadata 被豁免（参见上面的说明）。
const TRUNCATABLE_FIELDS = ["input", "output"] as const;
type TruncatableField = (typeof TRUNCATABLE_FIELDS)[number];

// 截断操作所基于的最小结构——由 SavedSpan / SavedTrace（创建）
// 及其更新载荷满足。将此约束限定为该结构（而非 Record<string, unknown>）
// 可使 SavedSpan/SavedTrace 等接口类型保持可赋值。
type PayloadLike = { input?: unknown; output?: unknown; metadata?: unknown };

// 被截断的是哪种对象——仅用于日志消息。
type PayloadKind = "span" | "trace";

const BYTES_PER_MB = 1024 * 1024;

interface TruncationMarker {
  // snake_case：这是存储在该对象上的线上数据，与后端 / Python SDK 的标记一致。
  opik_truncated: true;
  reason: string;
}

const truncationMarker = (sizeMb: number): TruncationMarker => ({
  opik_truncated: true,
  // 非有限大小意味着该值无法被序列化以进行测量（参见 fieldSizeMb）；
  // 报告该情况，而不是伪造的 "InfinityMB"（Math.round(Infinity) === Infinity）。
  reason: Number.isFinite(sizeMb)
    ? `<omitted_due_to_size_${Math.round(sizeMb)}MB_error_code_413_400>`
    : `<omitted_unserializable_error_code_413_400>`,
});

// 值的序列化大小（以 MB 计）——它在线上占用的重量（若序列化为空则返回 0）。
// 任何序列化失败——RangeError（字符串超过 V8 约 512 MiB 的上限）或
// TypeError（循环引用、BigInt、抛出异常的 toJSON 等）——都会返回 Infinity，因此无论
// 配置的上限如何，该字段总会被截断。我们在这里无法序列化的值同样无法发送，
// 因此将其降级为标记可避免下游 span/trace 创建抛出异常；一个有限的
// 哨兵值会让较大的上限把无法发送的载荷漏到线上。
const fieldSizeMb = (value: unknown): number => {
  try {
    const json = JSON.stringify(value);
    return json ? Buffer.byteLength(json, "utf8") / BYTES_PER_MB : 0;
  } catch {
    return Infinity;
  }
};

/**
 * 返回 `payload` 的浅拷贝，其中超大的字段被替换为截断
 * 标记（或原样返回 `payload`），以及被截断字段的列表。
 */
export const truncatePayloadFields = <T extends PayloadLike>(
  payload: T,
  maxSizeMb: number,
): { result: T; truncated: TruncatableField[] } => {
  const sizes = {} as Record<TruncatableField, number>;
  for (const field of TRUNCATABLE_FIELDS) {
    if (payload[field] != null) {
      sizes[field] = fieldSizeMb(payload[field]);
    }
  }
  if (Object.keys(sizes).length === 0) {
    return { result: payload, truncated: [] };
  }

  const overrides: Partial<Record<TruncatableField, TruncationMarker>> = {};

  // 第 1 遍——各自超过限制的字段（常见的“单个巨大字段”情况）。
  for (const field of TRUNCATABLE_FIELDS) {
    if (sizes[field] !== undefined && sizes[field] > maxSizeMb) {
      overrides[field] = truncationMarker(sizes[field]);
    }
  }

  // 第 2 遍——每个对象的硬上限：如果仍为内联的 input+output 合计超过限制，则同时截断
  // 剩余的可截断字段。复用第 1 遍的 `sizes`，而不是在这里重新序列化
  // 整个对象——那个 JSON.stringify 运行在热发送路径上（在第 1 遍和
  // API 客户端的基础上），因此在高吞吐量、大载荷下会不必要地阻塞事件循环。
  // 在第 1 遍中已被截断的字段只是一个很小的标记，因此其贡献约为 0；metadata 和其他
  // 不可截断字段按构造被排除在外（只有 input/output 被求和）。
  const inlineTotalMb = TRUNCATABLE_FIELDS.reduce(
    (sum, field) => sum + (overrides[field] ? 0 : (sizes[field] ?? 0)),
    0,
  );
  if (inlineTotalMb > maxSizeMb) {
    for (const field of TRUNCATABLE_FIELDS) {
      if (sizes[field] !== undefined && overrides[field] === undefined) {
        overrides[field] = truncationMarker(sizes[field]);
      }
    }
  }

  const truncated = Object.keys(overrides) as TruncatableField[];
  if (truncated.length === 0) {
    return { result: payload, truncated: [] };
  }
  return { result: { ...payload, ...overrides } as T, truncated };
};

/**
 * 若 span/trace 载荷（创建或更新）的字段超过每个对象的
 * 限制，则将其截断，并记录一条警告。返回浅拷贝（或原样返回
 * 输入）。`maxSizeMb <= 0` 时禁用该检查。`kind`/`id` 仅
 * 用于日志消息。
 */
export const truncatePayloadIfNeeded = <T extends PayloadLike>(
  payload: T,
  maxSizeMb: number,
  kind: PayloadKind = "span",
  id?: string,
): T => {
  if (!maxSizeMb || maxSizeMb <= 0) {
    return payload;
  }
  const { result, truncated } = truncatePayloadFields(payload, maxSizeMb);
  if (truncated.length > 0) {
    const label = kind.charAt(0).toUpperCase() + kind.slice(1);
    logger.warn(
      `${label} '${id ?? "unknown"}' exceeded the payload size limit of ${maxSizeMb} MB; ` +
        `truncated field(s): ${truncated.join(", ")}. ` +
        `Log large payloads as attachments to avoid truncation.`,
    );
  }
  return result;
};
