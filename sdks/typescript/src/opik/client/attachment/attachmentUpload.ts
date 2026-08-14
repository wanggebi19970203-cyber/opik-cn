import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import type { ExtractedAttachment } from "./attachmentExtraction";

/**
 * 将提取出的 base64 数据块作为 Opik 附件上传（与 Python SDK 的
 * file_upload 保持一致）。后端决定路径：`BEMinIO` 上传 id 表示“将字节
 * PUT 到单一的本地 URL”；其他情况则是 S3 分片上传（将每个分片 PUT 到其
 * 预签名 URL，收集 ETag，然后完成上传）。
 */

const LOCAL_UPLOAD_MAGIC_ID = "BEMinIO";
const PART_SIZE_BYTES = 5 * 1024 * 1024; // S3 分片最小大小

// 从 OpikConfig 解析一次得到的静态配置；包含对本地后端执行原始 PUT
// 所需的内容（该 PUT 绕过了生成的客户端，因此必须自行提供鉴权）。
export interface AttachmentUploadConfig {
  minSizeBytes: number;
  apiUrl: string;
  workspaceName: string;
  apiKey?: string;
  extraHeaders?: Record<string, string>;
}

export interface AttachmentUploadTarget {
  entityType: "span" | "trace";
  entityId: string;
  projectName?: string;
}

const putBytes = async (
  url: string,
  bytes: Buffer,
  headers?: Record<string, string>,
): Promise<string | null> => {
  const response = await fetch(url, {
    method: "PUT",
    body: bytes as unknown as BodyInit,
    headers,
  });
  if (!response.ok) {
    throw new Error(
      `attachment PUT failed: ${response.status} ${response.statusText}`,
    );
  }
  return response.headers.get("etag");
};

export const uploadInlineAttachment = async (
  api: OpikApiClientTemp,
  config: AttachmentUploadConfig,
  target: AttachmentUploadTarget,
  attachment: ExtractedAttachment,
): Promise<void> => {
  const { data, fileName, mimeType } = attachment;
  const numOfFileParts = Math.max(1, Math.ceil(data.length / PART_SIZE_BYTES));
  const path = Buffer.from(config.apiUrl, "utf8").toString("base64");

  const response = await api.attachments.startMultiPartUpload(
    {
      fileName,
      numOfFileParts,
      mimeType,
      entityType: target.entityType,
      entityId: target.entityId,
      path,
      projectName: target.projectName,
    },
    api.requestOptions,
  );

  if (response.uploadId === LOCAL_UPLOAD_MAGIC_ID) {
    // 本地后端：对整个文件执行一次带鉴权的 PUT，无需完成调用。
    const headers: Record<string, string> = {
      "Content-Type": mimeType,
      "Comet-Workspace": config.workspaceName,
      ...config.extraHeaders,
    };
    if (config.apiKey) {
      headers.authorization = config.apiKey;
    }
    await putBytes(response.preSignUrls[0], data, headers);
    return;
  }

  // 云端：将每个分片 PUT 到其预签名的 S3 URL（自鉴权），收集
  // ETag，然后在后端完成分片上传。
  const uploadedFileParts = [];
  for (let i = 0; i < response.preSignUrls.length; i++) {
    const start = i * PART_SIZE_BYTES;
    const chunk = data.subarray(start, start + PART_SIZE_BYTES);
    const eTag = await putBytes(response.preSignUrls[i], chunk);
    if (!eTag) {
      throw new Error(
        `attachment upload part ${i + 1} returned no ETag; cannot complete multipart upload`,
      );
    }
    uploadedFileParts.push({ eTag, partNumber: i + 1 });
  }
  await api.attachments.completeMultiPartUpload(
    {
      fileName,
      entityType: target.entityType,
      entityId: target.entityId,
      fileSize: data.length,
      mimeType,
      uploadId: response.uploadId,
      uploadedFileParts,
      projectName: target.projectName,
    },
    api.requestOptions,
  );
};
