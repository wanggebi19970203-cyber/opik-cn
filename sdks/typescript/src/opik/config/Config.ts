import { logger } from "@/utils/logger";
import fs from "fs";
import os from "os";
import path from "path";
import ini from "ini";
import { RequestOptions } from "@/types/request";
import "dotenv/config";

export interface OpikConfig {
  apiKey: string;
  apiUrl?: string;
  projectName: string;
  workspaceName: string;
  environment?: string;
  requestOptions?: RequestOptions;
  batchDelayMs?: number;
  holdUntilFlush?: boolean;
  promptCacheTtlSeconds?: number;
  trackDisable?: boolean;
  // 每个对象的载荷上限（MB）：span/trace 的输入/输出超过此大小会在发送前截断；
  // 元数据不受此限制，且不计入该度量（与 Python SDK 保持一致）。<= 0 表示禁用。默认 20。
  maxPayloadSizeMb?: number;
  // 从 span/trace 的输入/输出/元数据中提取内联 base64 数据块（例如图片），
  // 并在发送前将它们作为附件上传，使其不计入大小上限
  // （与 Python SDK 保持一致）。默认 true。
  isAttachmentExtractionActive?: boolean;
  // 内联 base64 数据块被提取为附件前的最小长度（以编码字符/字节计）；
  // 更小的数据块保留为内联。此判断基于*编码后*字符串长度，因此
  // 解码后的数据块约为其 3/4（默认值 256000 提取解码后约 192 KB+ 的数据块）。
  // 基于编码长度进行判断与 Python SDK 保持一致，并避免仅为测量大小而解码每个候选。
  // 默认 256000。
  minBase64EmbeddedAttachmentSize?: number;
}

export interface ConstructorOpikConfig extends Omit<OpikConfig, "environment"> {
  headers?: Record<string, string>;
}

const CONFIG_FILE_PATH_DEFAULT = path.join(os.homedir(), ".opik.config");

export const DEFAULT_CONFIG: Required<
  Omit<OpikConfig, "requestOptions" | "environment" | "promptCacheTtlSeconds">
> = {
  apiKey: "",
  apiUrl: "https://www.comet.com/opik/api",
  projectName: "Default Project",
  workspaceName: "default",
  batchDelayMs: 300,
  holdUntilFlush: false,
  trackDisable: false,
  maxPayloadSizeMb: 20,
  isAttachmentExtractionActive: true,
  minBase64EmbeddedAttachmentSize: 256_000,
};

function filterUndefined<T extends object>(obj: Partial<T>): Partial<T> {
  return Object.fromEntries(
    Object.entries(obj).filter(([, value]) => value !== undefined),
  ) as Partial<T>;
}

function parseBooleanFlag(value: string | undefined): boolean | undefined {
  if (value === undefined) {
    return undefined;
  }
  return ["1", "true", "yes"].includes(String(value).toLowerCase());
}

function loadFromEnv(): Partial<OpikConfig> {
  return filterUndefined({
    apiKey: process.env.OPIK_API_KEY,
    apiUrl: process.env.OPIK_URL_OVERRIDE,
    projectName: process.env.OPIK_PROJECT_NAME,
    workspaceName: process.env.OPIK_WORKSPACE,
    environment: process.env.OPIK_ENVIRONMENT,
    batchDelayMs: process.env.OPIK_BATCH_DELAY_MS
      ? Number(process.env.OPIK_BATCH_DELAY_MS)
      : undefined,
    holdUntilFlush: parseBooleanFlag(process.env.OPIK_HOLD_UNTIL_FLUSH),
    trackDisable: parseBooleanFlag(process.env.OPIK_TRACK_DISABLE),
    // 非数字值（例如带单位的笔误 "20MB"）必须回退到默认值，而不是 NaN：
    // NaN 会通过 filterUndefined 和下游的 `??` 保护，从而静默地完全禁用
    // 大小保护——这比不设置该变量更糟糕。
    maxPayloadSizeMb:
      process.env.OPIK_MAX_PAYLOAD_SIZE_MB &&
      Number.isFinite(Number(process.env.OPIK_MAX_PAYLOAD_SIZE_MB))
        ? Number(process.env.OPIK_MAX_PAYLOAD_SIZE_MB)
        : undefined,
    isAttachmentExtractionActive: parseBooleanFlag(
      process.env.OPIK_IS_ATTACHMENT_EXTRACTION_ACTIVE,
    ),
    // 非数字值回退到默认值（参见上面的 maxPayloadSizeMb）。
    minBase64EmbeddedAttachmentSize:
      process.env.OPIK_MIN_BASE64_EMBEDDED_ATTACHMENT_SIZE &&
      Number.isFinite(
        Number(process.env.OPIK_MIN_BASE64_EMBEDDED_ATTACHMENT_SIZE),
      )
        ? Number(process.env.OPIK_MIN_BASE64_EMBEDDED_ATTACHMENT_SIZE)
        : undefined,
    // parseInt 对非数字字符串返回 NaN；`|| 1` 在 Math.max 强制最小值之前将 NaN 转换为 1
    promptCacheTtlSeconds: process.env.OPIK_PROMPT_CACHE_TTL_SECONDS
      ? Math.max(
          1,
          parseInt(process.env.OPIK_PROMPT_CACHE_TTL_SECONDS, 10) || 1,
        )
      : undefined,
  });
}

function expandPath(filePath: string): string {
  return filePath.replace(/^~(?=$|\/|\\)/, os.homedir());
}

function loadFromConfigFile(): Partial<OpikConfig> {
  const configFilePath =
    process.env.OPIK_CONFIG_PATH || CONFIG_FILE_PATH_DEFAULT;
  const expandedConfigFilePath = expandPath(configFilePath);

  if (!fs.existsSync(expandedConfigFilePath)) {
    if (process.env.OPIK_CONFIG_PATH) {
      throw new Error(`Config file not found at ${expandedConfigFilePath}`);
    }

    return {};
  }

  try {
    const config = ini.parse(fs.readFileSync(expandedConfigFilePath, "utf8"));

    if (!config.opik) {
      return {};
    }

    // 只有身份/字符串设置从配置文件读取。数值和标志类设置
    // （batchDelayMs、maxPayloadSizeMb、isAttachmentExtractionActive、
    // minBase64EmbeddedAttachmentSize 等）仅通过 OPIK_* 环境变量配置。
    return filterUndefined({
      apiKey: config.opik.api_key,
      apiUrl: config.opik.url_override,
      projectName: config.opik.project_name,
      workspaceName: config.opik.workspace,
      trackDisable: parseBooleanFlag(config.opik.track_disable),
    });
  } catch (error) {
    logger.error(
      `Error loading config file ${expandedConfigFilePath}: ${error}`,
    );

    return {};
  }
}

export function loadConfig(
  explicit?: Partial<ConstructorOpikConfig>,
): OpikConfig {
  const envConfig = loadFromEnv();
  const fileConfig = loadFromConfigFile();

  const { headers: _, ...explicitConfig } = explicit || {};

  return validateConfig({
    ...DEFAULT_CONFIG,
    ...fileConfig,
    ...envConfig,
    ...filterUndefined(explicitConfig),
  });
}

export function validateConfig(config: OpikConfig) {
  if (!config.apiUrl) {
    throw new Error("OPIK_URL_OVERRIDE is not set");
  }

  // 当禁用追踪时，SDK 不会发送数据，因此不需要后端凭据。
  // 跳过云端凭据检查，使已埋点的应用可以在没有 API 密钥或本地部署的情况下运行。
  if (config.trackDisable) {
    return config;
  }

  const isCloudHost = isCloud(config.apiUrl);

  if (isCloudHost && !config.apiKey) {
    throw new Error("OPIK_API_KEY is not set");
  }

  if (isCloudHost && !config.workspaceName) {
    throw new Error("OPIK_WORKSPACE is not set");
  }

  return config;
}

function isCloud(apiUrl: string) {
  return new URL(apiUrl).hostname.endsWith("comet.com");
}
