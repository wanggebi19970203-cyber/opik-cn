import { SavedTrace } from "@/tracer/Trace";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { truncatePayloadIfNeeded } from "./payloadTruncation";
import { DEFAULT_CONFIG } from "@/config/Config";
import {
  extractAndUploadAttachments,
  type AttachmentUploadConfig,
  type AttachmentPayload,
} from "./attachment";

export class TraceBatchQueue extends BatchQueue<SavedTrace> {
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
      name: "TraceBatchQueue",
    });
  }

  protected getId(entity: SavedTrace) {
    return entity.id;
  }

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
      { entityType: "trace", entityId, projectName: payload.projectName },
      payload,
    );
  }

  // 在截断之前（启用时）提取内联 base64 附件，使图片变为
  // 附件，不再计入每个对象的大小上限。@track 会把最外层调用的输入/输出
  // 镜像到 trace 上，因此 trace 与 span 一样可能携带超大的载荷，
  // 需要相同的防护。
  protected async createEntities(traces: SavedTrace[]) {
    const payload: SavedTrace[] = [];
    for (const trace of traces) {
      const extracted = await this.extractAttachments(trace, trace.id);
      payload.push(
        truncatePayloadIfNeeded(
          extracted,
          this.maxPayloadSizeMb ?? DEFAULT_CONFIG.maxPayloadSizeMb,
          "trace",
          trace.id,
        ),
      );
    }
    await this.api.traces.createTraces(
      { traces: payload },
      this.api.requestOptions,
    );
  }

  protected async getEntity(id: string) {
    return (await this.api.traces.getTraceById(
      id,
      {},
      this.api.requestOptions,
    )) as SavedTrace;
  }

  protected async updateEntity(id: string, updates: Partial<SavedTrace>) {
    const extracted = await this.extractAttachments(updates, id);
    const body = truncatePayloadIfNeeded(
      extracted,
      this.maxPayloadSizeMb ?? DEFAULT_CONFIG.maxPayloadSizeMb,
      "trace",
      id,
    );
    await this.api.traces.updateTrace(id, { body }, this.api.requestOptions);
  }

  protected async deleteEntities(ids: string[]) {
    await this.api.traces.deleteTraces({ ids }, this.api.requestOptions);
  }
}
