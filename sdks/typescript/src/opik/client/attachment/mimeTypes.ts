/**
 * 对解码后的 base64 数据块进行魔数（magic-byte）MIME 探测。识别二进制媒体类型
 * （PNG/JPEG/GIF/WebP/PDF/SVG/MP4）以及 JSON（以 `{`/`[` 开头），与 Python SDK 的
 * `detect_mime_type` 保持一致；其他情况返回 null 并保留为内联。
 */

const startsWith = (
  bytes: Buffer,
  signature: number[],
  offset = 0,
): boolean => {
  if (bytes.length < offset + signature.length) {
    return false;
  }
  for (let i = 0; i < signature.length; i++) {
    if (bytes[offset + i] !== signature[i]) {
      return false;
    }
  }
  return true;
};

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const JPEG = [0xff, 0xd8, 0xff];
const GIF87A = [0x47, 0x49, 0x46, 0x38, 0x37, 0x61];
const GIF89A = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61];
const RIFF = [0x52, 0x49, 0x46, 0x46];
const WEBP = [0x57, 0x45, 0x42, 0x50];
const PDF = [0x25, 0x50, 0x44, 0x46];
const FTYP = [0x66, 0x74, 0x79, 0x70]; // "ftyp" 盒子，在 MP4 中出现在字节偏移量 4 处

export const detectMimeType = (bytes: Buffer): string | null => {
  if (startsWith(bytes, PNG)) return "image/png";
  // JPEG（与 Python SDK 保持一致）：仅有 SOI 头还不够——完整的 JPEG
  // 以 EOI 标记（FFD9）结尾。仅含头部/被截断的数据块会落到其他
  // 检查中，这样仅仅以 FFD8FF 开头的随机数据不会被误判为图片。
  if (
    startsWith(bytes, JPEG) &&
    bytes.length >= 2 &&
    bytes[bytes.length - 2] === 0xff &&
    bytes[bytes.length - 1] === 0xd9
  ) {
    return "image/jpeg";
  }
  if (startsWith(bytes, GIF87A) || startsWith(bytes, GIF89A))
    return "image/gif";
  if (startsWith(bytes, RIFF) && startsWith(bytes, WEBP, 8))
    return "image/webp";
  if (startsWith(bytes, PDF)) return "application/pdf";
  if (startsWith(bytes, FTYP, 4)) return "video/mp4";

  // 不区分大小写，在前 1 KB 内任意位置查找（与 Python SDK 保持一致）——可捕获
  // 在 <svg> 标签之前以 DOCTYPE、XML 注释或样式表 PI 开头的 SVG。
  const head = bytes.subarray(0, 1024).toString("utf8").toLowerCase();
  if (head.includes("<svg")) {
    return "image/svg+xml";
  }

  // JSON（与 Python SDK 保持一致）：前约 100 个字节必须是合法的 UTF-8，并且在
  // 去除前导空白后以 `{` 或 `[` 开头。致命的解码错误意味着二进制
  // 数据块（或在 100 字节窗口处被截断的多字节字符）被视为非 JSON。
  try {
    const text = new TextDecoder("utf-8", { fatal: true })
      .decode(bytes.subarray(0, 100))
      .trimStart();
    if (text.startsWith("{") || text.startsWith("[")) {
      return "application/json";
    }
  } catch {
    // 采样窗口内不是合法的 UTF-8 -> 非 JSON
  }
  return null;
};

const EXTENSIONS: Record<string, string> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/gif": "gif",
  "image/webp": "webp",
  "application/pdf": "pdf",
  "image/svg+xml": "svg",
  "video/mp4": "mp4",
  "application/json": "json",
};

export const fileExtensionForMimeType = (mimeType: string): string =>
  EXTENSIONS[mimeType] ?? "bin";
