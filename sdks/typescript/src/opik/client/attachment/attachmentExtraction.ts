import { detectMimeType, fileExtensionForMimeType } from "./mimeTypes";

/**
 * 客户端 base64 附件提取（与 Python SDK 保持一致，属于 OPIK-7335 系列）。
 *
 * span/trace 的 `input`/`output`/`metadata` 中较大的内联 base64 数据块（例如图片）
 * 会使载荷膨胀，并可能触发每个对象的大小上限。此函数遍历这些字段，将每个
 * 识别出的数据块替换为紧凑的 `[<context>-attachment-...-sdk.<ext>]` 占位符，
 * 并返回解码后的字节，以便调用方将其作为真正的附件上传。在大小测量之前
 * 运行此逻辑，正是让图片绕过大小上限的关键。
 *
 * 数据块通过手动的单遍字符扫描（参见 `extractFromString`）来查找，而不是
 * 正则表达式。V8 的正则引擎会对数 MB 的连续 base64 串抛出
 * `RangeError: Maximum call stack size exceeded`——对 base64 字符集的无界量词会回溯到
 * 调用栈中——这会静默地跳过提取，而这种情况恰好正是本功能所针对的大媒体场景
 * （已复现：内联图片 >= ~4 MB base64 会导致旧的正则崩溃）。线性扫描的复杂度为 O(n)，
 * 不会溢出栈。
 *
 * 纯函数且不产生变更：未提取任何内容时返回相同的引用。
 */

const FIELDS = ["input", "output", "metadata"] as const;
type Field = (typeof FIELDS)[number];

export type AttachmentSource = {
  input?: unknown;
  output?: unknown;
  metadata?: unknown;
};

export interface ExtractedAttachment {
  data: Buffer;
  fileName: string;
  mimeType: string;
}

const createAttachmentFileName = (
  context: string,
  mimeType: string,
): string => {
  const random = Math.floor(Math.random() * 99999999) + 1;
  const extension = fileExtensionForMimeType(mimeType);
  return `${context}-attachment-${random}-${Date.now()}-sdk.${extension}`;
};

// 解码扫描到的一段字符。URL 安全的 base64（用 `-`/`_` 代替 `+`/`/`，例如 google.genai 会发出这种）
// 会先被规范化为标准字符集——Node 的 base64 解码器会静默地丢弃 `-`/`_`，
// 这会在第一个此类字符处损坏字节并截断数据块（与
// Python SDK 的 base64_normalizer 保持一致，OPIK-6387）。这段字符按构造来说本身是合法的 base64，
// 因此 Buffer.from 永远不会抛出异常；解码结果为空意味着“不是真正的数据块”。
const decodeBase64 = (base64: string): Buffer | null => {
  const normalized =
    base64.includes("-") || base64.includes("_")
      ? base64.replace(/[-_]/g, (c) => (c === "-" ? "+" : "/"))
      : base64;
  const decoded = Buffer.from(normalized, "base64");
  return decoded.length > 0 ? decoded : null;
};

const isBase64Char = (code: number): boolean =>
  (code >= 65 && code <= 90) || // A-Z
  (code >= 97 && code <= 122) || // a-z
  (code >= 48 && code <= 57) || // 0-9
  code === 43 || // +
  code === 47 || // /
  code === 45 || // - （+ 的 URL 安全 base64 别名，例如 google.genai — OPIK-6387）
  code === 95; // _ （/ 的 URL 安全 base64 别名）
const EQUALS = 61; // '='

// 紧邻一段字符之前的可选 `data:<mime>;base64,` 前缀会被一并吸收，以便替换整个 URI，
// 而不仅仅是 base64 主体。我们回退搜索到真正的 `data:`（较长的 mime/参数
// 头可能超过任何固定窗口——sdks/typescript/AGENTS.md 禁止固定长度的回看），并
// 用一个小的锚定正则确认该片段确实是前缀。`:` 不是 base64 字符，因此我们找到的任何
// `data:` 都位于该段字符之外，而 `;base64,$` 锚点会拒绝伪造的 `data:`。
const DATA_URI_PREFIX_RE = /data:[^,]*;base64,$/;

const extractFromString = (
  value: string,
  context: Field,
  attachments: ExtractedAttachment[],
  minChars: number,
): string => {
  // 如果整个字符串比最小长度还短，就不可能是匹配的数据块（与
  // Python SDK 保持一致），提前跳过短字符串可避免扫描普通文本。
  if (value.length < minChars) {
    return value;
  }
  const parts: string[] = [];
  let lastEnd = 0;
  let changed = false;
  const n = value.length;
  let i = 0;

  while (i < n) {
    if (!isBase64Char(value.charCodeAt(i))) {
      i++;
      continue;
    }
    // 消费一段最大长度的 base64 字符，然后是至多两个 '=' 填充字符。
    let end = i;
    while (end < n && isBase64Char(value.charCodeAt(end))) {
      end++;
    }
    let pad = 0;
    while (end < n && value.charCodeAt(end) === EQUALS && pad < 2) {
      end++;
      pad++;
    }

    const runLength = end - i;
    if (runLength >= minChars) {
      const decoded = decodeBase64(value.slice(i, end));
      const mimeType = decoded ? detectMimeType(decoded) : null;
      // 像 Python SDK 一样，将未识别的数据块（纯 base64、未知类型）保留为内联。
      if (decoded && mimeType) {
        // 吸收前面的 `data:<mime>;base64,` 前缀，以便替换整个 URI。
        let replaceStart = i;
        const dataStart = value.lastIndexOf("data:", i);
        if (dataStart >= 0 && DATA_URI_PREFIX_RE.test(value.slice(dataStart, i))) {
          replaceStart = dataStart;
        }
        const fileName = createAttachmentFileName(context, mimeType);
        parts.push(value.slice(lastEnd, replaceStart));
        parts.push(`[${fileName}]`);
        lastEnd = end;
        attachments.push({ data: decoded, fileName, mimeType });
        changed = true;
      }
    }
    i = end;
  }

  if (!changed) {
    return value;
  }
  parts.push(value.slice(lastEnd));
  return parts.join("");
};

// `seen` 用于防止循环引用：如果载荷中存在环，否则会无限递归。
// 已访问过的对象/数组会原样返回（其中没有可提取的内容）。
const walk = (
  value: unknown,
  context: Field,
  attachments: ExtractedAttachment[],
  minChars: number,
  seen: WeakSet<object>,
): unknown => {
  if (typeof value === "string") {
    return extractFromString(value, context, attachments, minChars);
  }
  if (Array.isArray(value)) {
    if (seen.has(value)) return value;
    seen.add(value);
    let changed = false;
    const out = value.map((element) => {
      const walked = walk(element, context, attachments, minChars, seen);
      if (walked !== element) changed = true;
      return walked;
    });
    return changed ? out : value;
  }
  if (value !== null && typeof value === "object") {
    if (seen.has(value)) return value;
    seen.add(value);
    let changed = false;
    const out: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) {
      const walked = walk(item, context, attachments, minChars, seen);
      if (walked !== item) changed = true;
      out[key] = walked;
    }
    return changed ? out : value;
  }
  return value;
};

/**
 * 遍历 `input`/`output`/`metadata`，将至少 `minSizeBytes` 长的 base64 数据块
 * 替换为占位符。返回应用了替换的浅拷贝（若未提取任何内容则返回原始
 * 引用），以及解码后的附件列表。
 */
export const extractInlineAttachments = <T extends AttachmentSource>(
  payload: T,
  minSizeBytes: number,
): { result: T; attachments: ExtractedAttachment[] } => {
  // 基于 base64 字符长度的阈值：4 个 base64 字符编码 3 个字节，Python SDK
  // 以 `floor(minSizeBytes / 4)` 组（每组 4 个）为门槛——与此保持一致（向上取整到整组）。
  const minChars = Math.max(4, Math.floor(minSizeBytes / 4) * 4);

  const attachments: ExtractedAttachment[] = [];
  const overrides: Partial<Record<Field, unknown>> = {};
  for (const field of FIELDS) {
    const original = payload[field];
    if (original == null) {
      continue;
    }
    // 每个字段使用新的 `seen`：该防护仅用于单个字段自身图内的环。
    const walked = walk(original, field, attachments, minChars, new WeakSet());
    if (walked !== original) {
      overrides[field] = walked;
    }
  }

  if (attachments.length === 0) {
    return { result: payload, attachments: [] };
  }
  return { result: { ...payload, ...overrides } as T, attachments };
};
