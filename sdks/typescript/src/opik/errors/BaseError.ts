import { logger } from "@/utils/logger";

/**
 * Opik SDK 所有错误的基类。
 * 为 SDK 中的错误处理提供标准化结构。
 */
export class OpikError extends Error {
  public readonly code: string;
  public readonly statusCode?: number;
  public readonly details?: Record<string, unknown>;
  public readonly originalError?: Error;

  constructor(options: {
    message: string;
    code: string;
    statusCode?: number;
    details?: Record<string, unknown>;
    originalError?: Error;
  }) {
    super(options.message);
    this.name = this.constructor.name;
    this.code = options.code;
    this.statusCode = options.statusCode;
    this.details = options.details;
    this.originalError = options.originalError;

    logger.error(this.message);

    // 在 TypeScript 中正确捕获堆栈跟踪
    Error.captureStackTrace?.(this, this.constructor);
  }

  /**
   * 将错误转换为 JSON 对象以便序列化。
   */
  toJSON() {
    return {
      name: this.name,
      message: this.message,
      code: this.code,
      statusCode: this.statusCode,
      details: this.details,
      originalError: this.originalError,
      stack: this.stack,
    };
  }
}
