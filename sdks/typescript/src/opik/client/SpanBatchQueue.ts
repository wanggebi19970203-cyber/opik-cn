import { SavedSpan } from "@/tracer/Span";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { truncatePayloadIfNeeded } from "./payloadTruncation";
import { DEFAULT_CONFIG } from "@/config/Config";
import {
  extractAndUploadAttachments,
  type AttachmentUploadConfig,
  type AttachmentPayload,
} from "./attachment";

type SpanUpdate = Partial<SavedSpan> & { traceId: string };

export class SpanBatchQueue extends BatchQueue<SavedSpan> {
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number,
    private readonly maxPayloadSizeMb?: number,
    private readonly attachmentUpload?: AttachmentUploadConfig,
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: true,
      enableDeleteBatch: true,
      name: "SpanBatchQueue",
    });
  }

  protected getId(entity: SavedSpan) {
    return entity.id;
  }

  // 在截断之前（启用时）提取内联 base64 附件，使图片变为
  // 附件，不再计入每个 span 的大小上限。
  private async extractAttachments<T extends AttachmentPayload>(
    payload: T,
    entityId: string,
  ): Promise<T> {
    if (!this.attachmentUpload) {
      return payload;
    }
    return extractAndUploadAttachments(
      this.api,
      this.attachmentUpload,
      { entityType: "span", entityId, projectName: payload.projectName },
      payload,
    );
  }

  protected async createEntities(spans: SavedSpan[]) {
    const payload: SavedSpan[] = [];
    for (const span of spans) {
      const extracted = await this.extractAttachments(span, span.id);
      payload.push(
        truncatePayloadIfNeeded(
          extracted,
          this.maxPayloadSizeMb ?? DEFAULT_CONFIG.maxPayloadSizeMb,
          "span",
          span.id,
        ),
      );
    }
    await this.api.spans.createSpans(
      { spans: payload },
      this.api.requestOptions,
    );
  }

  protected async getEntity(id: string) {
    return (await this.api.spans.getSpanById(
      id,
      {},
      this.api.requestOptions,
    )) as SavedSpan;
  }

  protected async updateEntity(id: string, updates: SpanUpdate) {
    const extracted = await this.extractAttachments(updates, id);
    const body = truncatePayloadIfNeeded(
      extracted,
      this.maxPayloadSizeMb ?? DEFAULT_CONFIG.maxPayloadSizeMb,
      "span",
      id,
    );
    await this.api.spans.updateSpan(id, { body }, this.api.requestOptions);
  }

  protected async deleteEntities(ids: string[]) {
    for (const id of ids) {
      await this.api.spans.deleteSpanById(id, this.api.requestOptions);
    }
  }
}
