// 此文件由 Fern 根据我们的 API 定义自动生成。

/**
 * @example
 *     {
 *         ids: ["ids"]
 *     }
 */
export interface BatchDeleteByProject {
    /** 要删除的 trace 的 ID */
    ids: string[];
    /** 可选。将删除范围限定到此项目。若省略，则每个 trace 的所属项目会被自动解析，并在其完整键下删除该 trace，因此无需知道 trace 所属的项目即可删除它。 */
    projectId?: string;
}
