// 此文件由 Fern 根据我们的 API 定义自动生成。

import type { BaseClientOptions, BaseRequestOptions } from "../../../../BaseClient.js";
import { type NormalizedClientOptions, normalizeClientOptions } from "../../../../BaseClient.js";
import { mergeHeaders, mergeOnlyDefinedHeaders } from "../../../../core/headers.js";
import * as core from "../../../../core/index.js";
import { toJson } from "../../../../core/json.js";
import * as environments from "../../../../environments.js";
import { handleNonStatusCodeError } from "../../../../errors/handleNonStatusCodeError.js";
import * as errors from "../../../../errors/index.js";
import * as serializers from "../../../../serialization/index.js";
import * as OpikApi from "../../../index.js";

export declare namespace DatasetsClient {
    export type Options = BaseClientOptions;

    export interface RequestOptions extends BaseRequestOptions {}
}

/**
 * 数据集相关资源
 */
export class DatasetsClient {
    protected readonly _options: NormalizedClientOptions<DatasetsClient.Options>;

    constructor(options: DatasetsClient.Options = {}) {
        this._options = normalizeClientOptions(options);
    }

    /**
     * 对数据集版本应用增量变更（添加、编辑、删除），并进行冲突检测。
     *
     * 该端点：
     * - 创建一个包含已应用变更的新版本
     * - 校验 baseVersion 是否与最新版本匹配（除非 override=true）
     * - 如果 baseVersion 已过期且未设置 override，则返回 409 Conflict
     *
     * 使用 `override=true` 查询参数以在 baseVersion 已过期时强制创建版本。
     *
     * 在请求体中同时设置 'copy_from_dataset_id' 和 'copy_from_version_id'，以从所提供的
     * (dataset, version) 配对中读取结转行，而非从目标的先前版本中读取。当这些字段为
     * null 时，结转行将从目标的先前版本中读取。
     *
     * @param {string} id
     * @param {OpikApi.ApplyDatasetItemChangesRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.NotFoundError}
     * @throws {@link OpikApi.ConflictError}
     *
     * @example
     *     await client.datasets.applyDatasetItemChanges("id", {
     *         body: {
     *             "key": "value"
     *         }
     *     })
     */
    public applyDatasetItemChanges(
        id: string,
        request: OpikApi.ApplyDatasetItemChangesRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetVersionPublic> {
        return core.HttpResponsePromise.fromPromise(this.__applyDatasetItemChanges(id, request, requestOptions));
    }

    private async __applyDatasetItemChanges(
        id: string,
        request: OpikApi.ApplyDatasetItemChangesRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetVersionPublic>> {
        const { override, body: _body } = request;
        const _queryParams: Record<string, unknown> = {
            override,
        };
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/items/changes`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: { ..._queryParams, ...requestOptions?.queryParams },
            requestType: "json",
            body: serializers.DatasetItemChangesPublic.jsonOrThrow(_body, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetVersionPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                case 409:
                    throw new OpikApi.ConflictError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/{id}/items/changes",
        );
    }

    /**
     * 更新多个数据集条目
     *
     * @param {OpikApi.DatasetItemBatchUpdate} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.datasets.batchUpdateDatasetItems({
     *         update: {}
     *     })
     */
    public batchUpdateDatasetItems(
        request: OpikApi.DatasetItemBatchUpdate,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__batchUpdateDatasetItems(request, requestOptions));
    }

    private async __batchUpdateDatasetItems(
        request: OpikApi.DatasetItemBatchUpdate,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/items/batch",
            ),
            method: "PATCH",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetItemBatchUpdate.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "PATCH",
            "/v1/private/datasets/items/batch",
        );
    }

    /**
     * 查找数据集
     *
     * @param {OpikApi.FindDatasetsRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.findDatasets()
     */
    public findDatasets(
        request: OpikApi.FindDatasetsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__findDatasets(request, requestOptions));
    }

    private async __findDatasets(
        request: OpikApi.FindDatasetsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetPagePublic>> {
        const { page, size, withExperimentsOnly, withOptimizationsOnly, promptId, projectId, name, sorting, filters } =
            request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            with_experiments_only: withExperimentsOnly,
            with_optimizations_only: withOptimizationsOnly,
            prompt_id: promptId,
            project_id: projectId,
            name,
            sorting,
            filters,
        };
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets",
            ),
            method: "GET",
            headers: _headers,
            queryParameters: { ..._queryParams, ...requestOptions?.queryParams },
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetPagePublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "GET", "/v1/private/datasets");
    }

    /**
     * 创建数据集
     *
     * @param {OpikApi.DatasetWrite} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.createDataset({
     *         name: "name"
     *     })
     */
    public createDataset(
        request: OpikApi.DatasetWrite,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__createDataset(request, requestOptions));
    }

    private async __createDataset(
        request: OpikApi.DatasetWrite,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetWrite.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "POST", "/v1/private/datasets");
    }

    /**
     * 基于数据集条目 ID 创建/更新数据集条目。
     * 每个条目的 'id' 字段是稳定的标识符和 upsert 键。
     * 提供该字段可更新已有条目，省略该字段则创建新条目。
     *
     * 同时设置 'copy_from_dataset_id' 和 'copy_from_version_id'，以从所提供的
     * (dataset, version) 配对中读取结转行，而非从目标的先前版本中读取。当这些字段为
     * null 时，结转行将从目标的先前版本中读取。
     *
     * @param {OpikApi.DatasetItemBatchWrite} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.createOrUpdateDatasetItems({
     *         items: [{
     *                 source: "manual",
     *                 data: {
     *                     "key": "value"
     *                 }
     *             }]
     *     })
     */
    public createOrUpdateDatasetItems(
        request: OpikApi.DatasetItemBatchWrite,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__createOrUpdateDatasetItems(request, requestOptions));
    }

    private async __createOrUpdateDatasetItems(
        request: OpikApi.DatasetItemBatchWrite,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/items",
            ),
            method: "PUT",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetItemBatchWrite.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "PUT", "/v1/private/datasets/items");
    }

    /**
     * 从上传的 CSV 文件创建数据集条目。CSV 的第一行应包含表头。处理以批次异步进行。
     *
     * @param {OpikApi.CreateDatasetItemsFromCsvRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     import { createReadStream } from "fs";
     *     await client.datasets.createDatasetItemsFromCsv({
     *         file: {
     *             "key": "value"
     *         },
     *         datasetId: "dataset_id"
     *     })
     */
    public createDatasetItemsFromCsv(
        request: OpikApi.CreateDatasetItemsFromCsvRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__createDatasetItemsFromCsv(request, requestOptions));
    }

    private async __createDatasetItemsFromCsv(
        request: OpikApi.CreateDatasetItemsFromCsvRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _body = await core.newFormData();
        _body.append("file", toJson(request.file));
        _body.append("dataset_id", request.datasetId);
        const _maybeEncodedRequest = await _body.getRequest();
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
                ..._maybeEncodedRequest.headers,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/items/from-csv",
            ),
            method: "POST",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            requestType: "file",
            duplex: _maybeEncodedRequest.duplex,
            body: _maybeEncodedRequest.body,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/items/from-csv",
        );
    }

    /**
     * 从上传的 JSON 或 JSONL 文件创建数据集条目。JSON 文件必须包含一个顶层的对象数组。
     * JSONL 文件的每个非空行包含一个 JSON 对象；不支持多行 JSON 对象。
     * 保留键（id、source、description、tags、evaluators、execution_policy）会被提取到
     * 相应的 DatasetItem 字段中；其余所有键构成条目的 data 映射，并保留其 JSON 类型。
     * 要将数据集条目关联到特定的 trace 或 span，请使用专用的 /items/from-traces 或 /items/from-spans 端点。
     * 处理以批次异步进行。启用数据集版本控制后，所提供的 id 将作为 upsert 键。
     *
     * @param {OpikApi.CreateDatasetItemsFromJsonRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     import { createReadStream } from "fs";
     *     await client.datasets.createDatasetItemsFromJson({
     *         file: {
     *             "key": "value"
     *         },
     *         datasetId: "dataset_id",
     *         format: "json"
     *     })
     */
    public createDatasetItemsFromJson(
        request: OpikApi.CreateDatasetItemsFromJsonRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__createDatasetItemsFromJson(request, requestOptions));
    }

    private async __createDatasetItemsFromJson(
        request: OpikApi.CreateDatasetItemsFromJsonRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _body = await core.newFormData();
        _body.append("file", toJson(request.file));
        _body.append("dataset_id", request.datasetId);
        _body.append(
            "format",
            serializers.CreateDatasetItemsFromJsonRequestFormat.jsonOrThrow(request.format, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
        );
        const _maybeEncodedRequest = await _body.getRequest();
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
                ..._maybeEncodedRequest.headers,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/items/from-json",
            ),
            method: "POST",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            requestType: "file",
            duplex: _maybeEncodedRequest.duplex,
            body: _maybeEncodedRequest.body,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/items/from-json",
        );
    }

    /**
     * 从带丰富元数据的 span 创建数据集条目
     *
     * @param {string} dataset_id
     * @param {OpikApi.CreateDatasetItemsFromSpansRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.createDatasetItemsFromSpans("dataset_id", {
     *         spanIds: ["span_ids"],
     *         enrichmentOptions: {}
     *     })
     */
    public createDatasetItemsFromSpans(
        dataset_id: string,
        request: OpikApi.CreateDatasetItemsFromSpansRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(
            this.__createDatasetItemsFromSpans(dataset_id, request, requestOptions),
        );
    }

    private async __createDatasetItemsFromSpans(
        dataset_id: string,
        request: OpikApi.CreateDatasetItemsFromSpansRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(dataset_id)}/items/from-spans`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.CreateDatasetItemsFromSpansRequest.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/{dataset_id}/items/from-spans",
        );
    }

    /**
     * 从带丰富元数据的 trace 创建数据集条目
     *
     * @param {string} dataset_id
     * @param {OpikApi.CreateDatasetItemsFromTracesRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.createDatasetItemsFromTraces("dataset_id", {
     *         traceIds: ["trace_ids"],
     *         enrichmentOptions: {}
     *     })
     */
    public createDatasetItemsFromTraces(
        dataset_id: string,
        request: OpikApi.CreateDatasetItemsFromTracesRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(
            this.__createDatasetItemsFromTraces(dataset_id, request, requestOptions),
        );
    }

    private async __createDatasetItemsFromTraces(
        dataset_id: string,
        request: OpikApi.CreateDatasetItemsFromTracesRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(dataset_id)}/items/from-traces`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.CreateDatasetItemsFromTracesRequest.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/{dataset_id}/items/from-traces",
        );
    }

    /**
     * 根据 ID 获取数据集
     *
     * @param {string} id
     * @param {OpikApi.GetDatasetByIdRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.getDatasetById("id")
     */
    public getDatasetById(
        id: string,
        request: OpikApi.GetDatasetByIdRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetPublic> {
        return core.HttpResponsePromise.fromPromise(this.__getDatasetById(id, request, requestOptions));
    }

    private async __getDatasetById(
        id: string,
        _request: OpikApi.GetDatasetByIdRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetPublic>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "GET", "/v1/private/datasets/{id}");
    }

    /**
     * 根据 ID 更新数据集
     *
     * @param {string} id
     * @param {OpikApi.DatasetUpdate} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.updateDataset("id", {
     *         name: "name"
     *     })
     */
    public updateDataset(
        id: string,
        request: OpikApi.DatasetUpdate,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__updateDataset(id, request, requestOptions));
    }

    private async __updateDataset(
        id: string,
        request: OpikApi.DatasetUpdate,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}`,
            ),
            method: "PUT",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetUpdate.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "PUT", "/v1/private/datasets/{id}");
    }

    /**
     * 根据 ID 删除数据集
     *
     * @param {string} id
     * @param {OpikApi.DeleteDatasetRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.deleteDataset("id")
     */
    public deleteDataset(
        id: string,
        request: OpikApi.DeleteDatasetRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__deleteDataset(id, request, requestOptions));
    }

    private async __deleteDataset(
        id: string,
        _request: OpikApi.DeleteDatasetRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}`,
            ),
            method: "DELETE",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "DELETE", "/v1/private/datasets/{id}");
    }

    /**
     * 根据名称删除数据集
     *
     * @param {OpikApi.DatasetIdentifier} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.deleteDatasetByName({
     *         datasetName: "dataset_name"
     *     })
     */
    public deleteDatasetByName(
        request: OpikApi.DatasetIdentifier,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__deleteDatasetByName(request, requestOptions));
    }

    private async __deleteDatasetByName(
        request: OpikApi.DatasetIdentifier,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/delete",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetIdentifier.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "POST", "/v1/private/datasets/delete");
    }

    /**
     * 使用以下两种模式之一删除数据集条目：
     * 1. **按 ID 删除**：提供 'item_ids' 以按条目的 ID 删除特定条目
     * 2. **按筛选条件删除**：提供 'dataset_id' 以及可选的 'filters'，以删除匹配条件的条目
     *
     * 使用筛选条件时，空的 'filters' 数组将删除指定数据集中的所有条目。
     *
     * @param {OpikApi.DatasetItemsDelete} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.datasets.deleteDatasetItems()
     */
    public deleteDatasetItems(
        request: OpikApi.DatasetItemsDelete = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__deleteDatasetItems(request, requestOptions));
    }

    private async __deleteDatasetItems(
        request: OpikApi.DatasetItemsDelete = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/items/delete",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetItemsDelete.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/items/delete",
        );
    }

    /**
     * 批量删除数据集
     *
     * @param {OpikApi.BatchDelete} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.deleteDatasetsBatch({
     *         ids: ["ids"]
     *     })
     */
    public deleteDatasetsBatch(
        request: OpikApi.BatchDelete,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__deleteDatasetsBatch(request, requestOptions));
    }

    private async __deleteDatasetsBatch(
        request: OpikApi.BatchDelete,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/delete-batch",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.BatchDelete.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/delete-batch",
        );
    }

    /**
     * 下载已完成导出任务的 CSV 文件。该端点代理文件下载，以避免暴露内部存储 URL。
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.NotFoundError}
     */
    public downloadDatasetExport(
        jobId: string,
        request: OpikApi.DownloadDatasetExportRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<ReadableStream<Uint8Array>> {
        return core.HttpResponsePromise.fromPromise(this.__downloadDatasetExport(jobId, request, requestOptions));
    }

    private async __downloadDatasetExport(
        jobId: string,
        _request: OpikApi.DownloadDatasetExportRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<ReadableStream<Uint8Array>>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher<ReadableStream<Uint8Array>>({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/export-jobs/${core.url.encodePathParam(jobId)}/download`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            responseType: "streaming",
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: _response.body, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/export-jobs/{jobId}/download",
        );
    }

    /**
     * 基于现有数据模式，使用 LLM 生成合成数据集样本
     *
     * @param {string} id
     * @param {OpikApi.DatasetExpansionWrite} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.expandDataset("id", {
     *         model: "gpt-4"
     *     })
     */
    public expandDataset(
        id: string,
        request: OpikApi.DatasetExpansionWrite,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetExpansionResponse> {
        return core.HttpResponsePromise.fromPromise(this.__expandDataset(id, request, requestOptions));
    }

    private async __expandDataset(
        id: string,
        request: OpikApi.DatasetExpansionWrite,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetExpansionResponse>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/expansions`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetExpansionWrite.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetExpansionResponse.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/{id}/expansions",
        );
    }

    /**
     * 查找带有实验条目的数据集条目
     *
     * @param {string} id
     * @param {OpikApi.FindDatasetItemsWithExperimentItemsRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.findDatasetItemsWithExperimentItems("id", {
     *         experimentIds: "experiment_ids"
     *     })
     */
    public findDatasetItemsWithExperimentItems(
        id: string,
        request: OpikApi.FindDatasetItemsWithExperimentItemsRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetItemPageCompare> {
        return core.HttpResponsePromise.fromPromise(
            this.__findDatasetItemsWithExperimentItems(id, request, requestOptions),
        );
    }

    private async __findDatasetItemsWithExperimentItems(
        id: string,
        request: OpikApi.FindDatasetItemsWithExperimentItemsRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetItemPageCompare>> {
        const { page, size, experimentIds, filters, sorting, search, truncate } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            experiment_ids: experimentIds,
            filters,
            sorting,
            search,
            truncate,
        };
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/items/experiments/items`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: { ..._queryParams, ...requestOptions?.queryParams },
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetItemPageCompare.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/{id}/items/experiments/items",
        );
    }

    /**
     * 根据名称获取数据集
     *
     * @param {OpikApi.DatasetIdentifierPublic} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.getDatasetByIdentifier({
     *         datasetName: "dataset_name"
     *     })
     */
    public getDatasetByIdentifier(
        request: OpikApi.DatasetIdentifierPublic,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetPublic> {
        return core.HttpResponsePromise.fromPromise(this.__getDatasetByIdentifier(request, requestOptions));
    }

    private async __getDatasetByIdentifier(
        request: OpikApi.DatasetIdentifierPublic,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetPublic>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/retrieve",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetIdentifierPublic.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/retrieve",
        );
    }

    /**
     * 获取数据集的实验条目统计信息
     *
     * @param {string} id
     * @param {OpikApi.GetDatasetExperimentItemsStatsRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.getDatasetExperimentItemsStats("id", {
     *         experimentIds: "experiment_ids"
     *     })
     */
    public getDatasetExperimentItemsStats(
        id: string,
        request: OpikApi.GetDatasetExperimentItemsStatsRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.ProjectStatsPublic> {
        return core.HttpResponsePromise.fromPromise(this.__getDatasetExperimentItemsStats(id, request, requestOptions));
    }

    private async __getDatasetExperimentItemsStats(
        id: string,
        request: OpikApi.GetDatasetExperimentItemsStatsRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.ProjectStatsPublic>> {
        const { experimentIds, filters } = request;
        const _queryParams: Record<string, unknown> = {
            experiment_ids: experimentIds,
            filters,
        };
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/items/experiments/items/stats`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: { ..._queryParams, ...requestOptions?.queryParams },
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.ProjectStatsPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/{id}/items/experiments/items/stats",
        );
    }

    /**
     * 获取数据集导出任务的当前状态
     *
     * @param {string} jobId
     * @param {OpikApi.GetDatasetExportJobRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.datasets.getDatasetExportJob("jobId")
     */
    public getDatasetExportJob(
        jobId: string,
        request: OpikApi.GetDatasetExportJobRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetExportJobPublic> {
        return core.HttpResponsePromise.fromPromise(this.__getDatasetExportJob(jobId, request, requestOptions));
    }

    private async __getDatasetExportJob(
        jobId: string,
        _request: OpikApi.GetDatasetExportJobRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetExportJobPublic>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/export-jobs/${core.url.encodePathParam(jobId)}`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetExportJobPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/export-jobs/{jobId}",
        );
    }

    /**
     * 获取工作空间中的所有导出任务。这用于在页面刷新后恢复导出面板的状态。
     *
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.getDatasetExportJobs()
     */
    public getDatasetExportJobs(
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetExportJobPublic[]> {
        return core.HttpResponsePromise.fromPromise(this.__getDatasetExportJobs(requestOptions));
    }

    private async __getDatasetExportJobs(
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetExportJobPublic[]>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/export-jobs",
            ),
            method: "GET",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.datasets.getDatasetExportJobs.Response.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/export-jobs",
        );
    }

    /**
     * 根据 ID 获取数据集条目
     *
     * @param {string} itemId
     * @param {OpikApi.GetDatasetItemByIdRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.getDatasetItemById("itemId")
     */
    public getDatasetItemById(
        itemId: string,
        request: OpikApi.GetDatasetItemByIdRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetItemPublic> {
        return core.HttpResponsePromise.fromPromise(this.__getDatasetItemById(itemId, request, requestOptions));
    }

    private async __getDatasetItemById(
        itemId: string,
        _request: OpikApi.GetDatasetItemByIdRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetItemPublic>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/items/${core.url.encodePathParam(itemId)}`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetItemPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/items/{itemId}",
        );
    }

    /**
     * 根据 ID 部分更新数据集条目。仅更新所提供的字段。
     *
     * @param {string} itemId
     * @param {OpikApi.PatchDatasetItemRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.datasets.patchDatasetItem("itemId", {
     *         body: {
     *             source: "manual",
     *             data: {
     *                 "key": "value"
     *             }
     *         }
     *     })
     */
    public patchDatasetItem(
        itemId: string,
        request: OpikApi.PatchDatasetItemRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__patchDatasetItem(itemId, request, requestOptions));
    }

    private async __patchDatasetItem(
        itemId: string,
        request: OpikApi.PatchDatasetItemRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const { body: _body } = request;
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/items/${core.url.encodePathParam(itemId)}`,
            ),
            method: "PATCH",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetItemWrite.jsonOrThrow(_body, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "PATCH",
            "/v1/private/datasets/items/{itemId}",
        );
    }

    /**
     * 获取数据集条目
     *
     * @param {string} id
     * @param {OpikApi.GetDatasetItemsRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.getDatasetItems("id")
     */
    public getDatasetItems(
        id: string,
        request: OpikApi.GetDatasetItemsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetItemPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__getDatasetItems(id, request, requestOptions));
    }

    private async __getDatasetItems(
        id: string,
        request: OpikApi.GetDatasetItemsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetItemPagePublic>> {
        const { page, size, version, filters, truncate } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            version,
            filters,
            truncate,
        };
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/items`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: { ..._queryParams, ...requestOptions?.queryParams },
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetItemPagePublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/{id}/items",
        );
    }

    /**
     * 获取数据集条目的输出列
     *
     * @param {string} id
     * @param {OpikApi.GetDatasetItemsOutputColumnsRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.getDatasetItemsOutputColumns("id")
     */
    public getDatasetItemsOutputColumns(
        id: string,
        request: OpikApi.GetDatasetItemsOutputColumnsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.PageColumns> {
        return core.HttpResponsePromise.fromPromise(this.__getDatasetItemsOutputColumns(id, request, requestOptions));
    }

    private async __getDatasetItemsOutputColumns(
        id: string,
        request: OpikApi.GetDatasetItemsOutputColumnsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.PageColumns>> {
        const { experimentIds } = request;
        const _queryParams: Record<string, unknown> = {
            experiment_ids: experimentIds,
        };
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/items/experiments/items/output/columns`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: { ..._queryParams, ...requestOptions?.queryParams },
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.PageColumns.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/{id}/items/experiments/items/output/columns",
        );
    }

    /**
     * 通过设置 viewed_at 时间戳将数据集导出任务标记为已查看。这用于跟踪用户是否已查看失败任务的错误消息。此操作是幂等的。
     *
     * @param {string} jobId
     * @param {OpikApi.MarkDatasetExportJobViewedRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.datasets.markDatasetExportJobViewed("jobId")
     */
    public markDatasetExportJobViewed(
        jobId: string,
        request: OpikApi.MarkDatasetExportJobViewedRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__markDatasetExportJobViewed(jobId, request, requestOptions));
    }

    private async __markDatasetExportJobViewed(
        jobId: string,
        _request: OpikApi.MarkDatasetExportJobViewedRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/export-jobs/${core.url.encodePathParam(jobId)}/mark-viewed`,
            ),
            method: "PUT",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "PUT",
            "/v1/private/datasets/export-jobs/{jobId}/mark-viewed",
        );
    }

    /**
     * 为数据集发起一个异步 CSV 导出任务。立即返回任务详情以供轮询。
     *
     * @param {string} id
     * @param {OpikApi.StartDatasetExportRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.startDatasetExport("id")
     */
    public startDatasetExport(
        id: string,
        request: OpikApi.StartDatasetExportRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetExportJobPublic> {
        return core.HttpResponsePromise.fromPromise(this.__startDatasetExport(id, request, requestOptions));
    }

    private async __startDatasetExport(
        id: string,
        _request: OpikApi.StartDatasetExportRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetExportJobPublic>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/export`,
            ),
            method: "POST",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetExportJobPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/{id}/export",
        );
    }

    /**
     * 流式获取数据集条目
     */
    public streamDatasetItems(
        request: OpikApi.DatasetItemStreamRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<ReadableStream<Uint8Array>> {
        return core.HttpResponsePromise.fromPromise(this.__streamDatasetItems(request, requestOptions));
    }

    private async __streamDatasetItems(
        request: OpikApi.DatasetItemStreamRequest,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<ReadableStream<Uint8Array>>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher<ReadableStream<Uint8Array>>({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                "v1/private/datasets/items/stream",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetItemStreamRequest.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            responseType: "streaming",
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: _response.body, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/items/stream",
        );
    }

    /**
     * 将最新已提交的数据集版本与当前草稿状态进行比较。该端点提供自上一版本提交以来所做更改的洞察。该比较计算最新版本快照与当前草稿之间的新增、修改、删除和未更改条目。
     *
     * @param {string} id
     * @param {OpikApi.CompareDatasetVersionsRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.datasets.compareDatasetVersions("id")
     */
    public compareDatasetVersions(
        id: string,
        request: OpikApi.CompareDatasetVersionsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetVersionDiff> {
        return core.HttpResponsePromise.fromPromise(this.__compareDatasetVersions(id, request, requestOptions));
    }

    private async __compareDatasetVersions(
        id: string,
        _request: OpikApi.CompareDatasetVersionsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetVersionDiff>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/versions/diff`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetVersionDiff.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/{id}/versions/diff",
        );
    }

    /**
     * 为特定数据集版本添加标签以便于引用（例如 'baseline'、'v1.0'、'production'）
     *
     * @param {string} id
     * @param {string} versionHash
     * @param {OpikApi.DatasetVersionTag} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.NotFoundError}
     * @throws {@link OpikApi.ConflictError}
     *
     * @example
     *     await client.datasets.createVersionTag("id", "versionHash", {
     *         tag: "tag"
     *     })
     */
    public createVersionTag(
        id: string,
        versionHash: string,
        request: OpikApi.DatasetVersionTag,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__createVersionTag(id, versionHash, request, requestOptions));
    }

    private async __createVersionTag(
        id: string,
        versionHash: string,
        request: OpikApi.DatasetVersionTag,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/versions/hash/${core.url.encodePathParam(versionHash)}/tags`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetVersionTag.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                case 409:
                    throw new OpikApi.ConflictError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/{id}/versions/hash/{versionHash}/tags",
        );
    }

    /**
     * 从数据集版本中移除标签。版本本身不会被删除，只会删除标签引用。
     *
     * @param {string} id
     * @param {string} versionHash
     * @param {string} tag
     * @param {OpikApi.DeleteVersionTagRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.datasets.deleteVersionTag("id", "versionHash", "tag")
     */
    public deleteVersionTag(
        id: string,
        versionHash: string,
        tag: string,
        request: OpikApi.DeleteVersionTagRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(
            this.__deleteVersionTag(id, versionHash, tag, request, requestOptions),
        );
    }

    private async __deleteVersionTag(
        id: string,
        versionHash: string,
        tag: string,
        _request: OpikApi.DeleteVersionTagRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<void>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/versions/${core.url.encodePathParam(versionHash)}/tags/${core.url.encodePathParam(tag)}`,
            ),
            method: "DELETE",
            headers: _headers,
            queryParameters: requestOptions?.queryParams,
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return { data: undefined, rawResponse: _response.rawResponse };
        }

        if (_response.error.reason === "status-code") {
            throw new errors.OpikApiError({
                statusCode: _response.error.statusCode,
                body: _response.error.body,
                rawResponse: _response.rawResponse,
            });
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "DELETE",
            "/v1/private/datasets/{id}/versions/{versionHash}/tags/{tag}",
        );
    }

    /**
     * 获取数据集的分页版本列表，按创建时间排序（最新的在前）
     *
     * @param {string} id
     * @param {OpikApi.ListDatasetVersionsRequest} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.datasets.listDatasetVersions("id")
     */
    public listDatasetVersions(
        id: string,
        request: OpikApi.ListDatasetVersionsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetVersionPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__listDatasetVersions(id, request, requestOptions));
    }

    private async __listDatasetVersions(
        id: string,
        request: OpikApi.ListDatasetVersionsRequest = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetVersionPagePublic>> {
        const { page, size } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
        };
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/versions`,
            ),
            method: "GET",
            headers: _headers,
            queryParameters: { ..._queryParams, ...requestOptions?.queryParams },
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetVersionPagePublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/datasets/{id}/versions",
        );
    }

    /**
     * 通过创建新版本（条目从指定版本复制）将数据集恢复到之前的版本状态。如果该版本已是最新版本，则按原样返回（无操作）。
     *
     * @param {string} id
     * @param {OpikApi.DatasetVersionRestorePublic} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     * @throws {@link OpikApi.ConflictError}
     *
     * @example
     *     await client.datasets.restoreDatasetVersion("id", {
     *         versionRef: "version_ref"
     *     })
     */
    public restoreDatasetVersion(
        id: string,
        request: OpikApi.DatasetVersionRestorePublic,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetVersionPublic> {
        return core.HttpResponsePromise.fromPromise(this.__restoreDatasetVersion(id, request, requestOptions));
    }

    private async __restoreDatasetVersion(
        id: string,
        request: OpikApi.DatasetVersionRestorePublic,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetVersionPublic>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/versions/restore`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetVersionRestorePublic.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetVersionPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                case 409:
                    throw new OpikApi.ConflictError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/{id}/versions/restore",
        );
    }

    /**
     * 根据版本名称（例如 'v1'、'v373'）获取特定版本。对于大型数据集，这比遍历所有版本进行分页更高效。
     *
     * @param {string} id
     * @param {OpikApi.DatasetVersionRetrieveRequestPublic} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.datasets.retrieveDatasetVersion("id", {
     *         versionName: "v1"
     *     })
     */
    public retrieveDatasetVersion(
        id: string,
        request: OpikApi.DatasetVersionRetrieveRequestPublic,
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetVersionPublic> {
        return core.HttpResponsePromise.fromPromise(this.__retrieveDatasetVersion(id, request, requestOptions));
    }

    private async __retrieveDatasetVersion(
        id: string,
        request: OpikApi.DatasetVersionRetrieveRequestPublic,
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetVersionPublic>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/versions/retrieve`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetVersionRetrieveRequestPublic.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetVersionPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "POST",
            "/v1/private/datasets/{id}/versions/retrieve",
        );
    }

    /**
     * 更新数据集版本的 change_description 和/或添加新标签
     *
     * @param {string} id
     * @param {string} versionHash
     * @param {OpikApi.DatasetVersionUpdatePublic} request
     * @param {DatasetsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.NotFoundError}
     * @throws {@link OpikApi.ConflictError}
     *
     * @example
     *     await client.datasets.updateDatasetVersion("id", "versionHash")
     */
    public updateDatasetVersion(
        id: string,
        versionHash: string,
        request: OpikApi.DatasetVersionUpdatePublic = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetVersionPublic> {
        return core.HttpResponsePromise.fromPromise(
            this.__updateDatasetVersion(id, versionHash, request, requestOptions),
        );
    }

    private async __updateDatasetVersion(
        id: string,
        versionHash: string,
        request: OpikApi.DatasetVersionUpdatePublic = {},
        requestOptions?: DatasetsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetVersionPublic>> {
        const _headers: core.Fetcher.Args["headers"] = mergeHeaders(
            this._options?.headers,
            mergeOnlyDefinedHeaders({
                "Comet-Workspace": requestOptions?.workspaceName ?? this._options?.workspaceName,
            }),
            requestOptions?.headers,
        );
        const _response = await core.fetcher({
            url: core.url.join(
                (await core.Supplier.get(this._options.baseUrl)) ??
                    (await core.Supplier.get(this._options.environment)) ??
                    environments.OpikApiEnvironment.Default,
                `v1/private/datasets/${core.url.encodePathParam(id)}/versions/hash/${core.url.encodePathParam(versionHash)}`,
            ),
            method: "PATCH",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DatasetVersionUpdatePublic.jsonOrThrow(request, {
                unrecognizedObjectKeys: "strip",
                omitUndefined: true,
            }),
            timeoutMs: (requestOptions?.timeoutInSeconds ?? this._options?.timeoutInSeconds ?? 60) * 1000,
            maxRetries: requestOptions?.maxRetries ?? this._options?.maxRetries,
            withCredentials: true,
            abortSignal: requestOptions?.abortSignal,
            fetchFn: this._options?.fetch,
            logging: this._options.logging,
        });
        if (_response.ok) {
            return {
                data: serializers.DatasetVersionPublic.parseOrThrow(_response.body, {
                    unrecognizedObjectKeys: "passthrough",
                    allowUnrecognizedUnionMembers: true,
                    allowUnrecognizedEnumValues: true,
                    skipValidation: true,
                    breadcrumbsPrefix: ["response"],
                }),
                rawResponse: _response.rawResponse,
            };
        }

        if (_response.error.reason === "status-code") {
            switch (_response.error.statusCode) {
                case 400:
                    throw new OpikApi.BadRequestError(_response.error.body, _response.rawResponse);
                case 404:
                    throw new OpikApi.NotFoundError(_response.error.body, _response.rawResponse);
                case 409:
                    throw new OpikApi.ConflictError(_response.error.body, _response.rawResponse);
                default:
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.body,
                        rawResponse: _response.rawResponse,
                    });
            }
        }

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "PATCH",
            "/v1/private/datasets/{id}/versions/hash/{versionHash}",
        );
    }
}
