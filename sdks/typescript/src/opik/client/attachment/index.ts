import { logger } from "@/utils/logger";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import {
  extractInlineAttachments,
  type AttachmentSource,
} from "./attachmentExtraction";
import {
  uploadInlineAttachment,
  type AttachmentUploadConfig,
  type AttachmentUploadTarget,
} from "./attachmentUpload";

export type { AttachmentUploadConfig, AttachmentUploadTarget, AttachmentSource };

// 批量队列所看到的 span/trace 载荷：可提取字段加上用于定位
// 已上传附件的项目名称。共享此类型，避免 SpanBatchQueue/TraceBatchQueue
// 各自重复声明。
export type AttachmentPayload = AttachmentSource & { projectName?: string };

/**
 * 从 span/trace 载荷中提取内联 base64 数据块，将其作为附件上传，并
 * 返回净化后的载荷（占位符替代数据块）。在任何大小测量之前运行此逻辑，
 * 使提取出的图片不计入每个 span 的大小上限。
 *
 * 尽力而为且非致命：即使提取或上传失败，写入仍会继续。
 * 上传失败时占位符会被保留（字段保持较小），并记录一条警告。
 */
export const extractAndUploadAttachments = async <T extends AttachmentSource>(
  api: OpikApiClientTemp,
  config: AttachmentUploadConfig,
  target: AttachmentUploadTarget,
  payload: T,
): Promise<T> => {
  let extraction: ReturnType<typeof extractInlineAttachments<T>>;
  try {
    extraction = extractInlineAttachments(payload, config.minSizeBytes);
  } catch (error) {
    // 仅记录消息：网络层错误的 `cause` 可能携带预签名的 S3 URL
    // （其中内嵌了 AWS 签名），因此绝不要序列化原始错误对象。
    logger.warn(
      `Attachment extraction skipped for ${target.entityType} '${target.entityId}': ` +
        `${error instanceof Error ? error.message : String(error)}`,
    );
    return payload;
  }

  if (extraction.attachments.length === 0) {
    return payload;
  }

  await Promise.all(
    extraction.attachments.map((attachment) =>
      uploadInlineAttachment(api, config, target, attachment).catch((error) => {
        // 仅记录消息——fetch 被拒绝时的 `cause` 可能持有预签名的 S3 URL。
        logger.warn(
          `Failed to upload extracted attachment '${attachment.fileName}' ` +
            `for ${target.entityType} '${target.entityId}': ` +
            `${error instanceof Error ? error.message : String(error)}`,
        );
      }),
    ),
  );

  return extraction.result;
};
