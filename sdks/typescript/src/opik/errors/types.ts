/**
 * 创建错误时的配置选项。
 */
export interface ErrorOptions {
  message: string;
  code: string;
  statusCode?: number;
  details?: Record<string, unknown>;
}
