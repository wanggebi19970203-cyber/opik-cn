// 此文件由 Fern 根据我们的 API 定义自动生成。

import type { BaseClientOptions, BaseRequestOptions } from "../../../../BaseClient.js";
import { type NormalizedClientOptions, normalizeClientOptions } from "../../../../BaseClient.js";
import { mergeHeaders, mergeOnlyDefinedHeaders } from "../../../../core/headers.js";
import * as core from "../../../../core/index.js";
import * as environments from "../../../../environments.js";
import { handleNonStatusCodeError } from "../../../../errors/handleNonStatusCodeError.js";
import * as errors from "../../../../errors/index.js";
import * as serializers from "../../../../serialization/index.js";
import * as OpikApi from "../../../index.js";

export declare namespace OptimizationsClient {
    export type Options = BaseClientOptions;

    export interface RequestOptions extends BaseRequestOptions {}
}

/**
 * 优化相关资源
 */
export class OptimizationsClient {
    protected readonly _options: NormalizedClientOptions<OptimizationsClient.Options>;

    constructor(options: OptimizationsClient.Options = {}) {
        this._options = normalizeClientOptions(options);
    }

    /**
     * 查找优化
     *
     * @param {OpikApi.FindOptimizationsRequest} request
     * @param {OptimizationsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.optimizations.findOptimizations()
     */
    public findOptimizations(
        request: OpikApi.FindOptimizationsRequest = {},
        requestOptions?: OptimizationsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.OptimizationPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__findOptimizations(request, requestOptions));
    }

    private async __findOptimizations(
        request: OpikApi.FindOptimizationsRequest = {},
        requestOptions?: OptimizationsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.OptimizationPagePublic>> {
        const { page, size, datasetId, name, datasetName, datasetDeleted, projectId, filters } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            dataset_id: datasetId,
            name,
            dataset_name: datasetName,
            dataset_deleted: datasetDeleted,
            project_id: projectId,
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
                "v1/private/optimizations",
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
                data: serializers.OptimizationPagePublic.parseOrThrow(_response.body, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "GET", "/v1/private/optimizations");
    }

    /**
     * 创建优化
     *
     * @param {OpikApi.OptimizationWrite} request
     * @param {OptimizationsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.optimizations.createOptimization({
     *         datasetName: "dataset_name",
     *         objectiveName: "objective_name",
     *         status: "running"
     *     })
     */
    public createOptimization(
        request: OpikApi.OptimizationWrite,
        requestOptions?: OptimizationsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__createOptimization(request, requestOptions));
    }

    private async __createOptimization(
        request: OpikApi.OptimizationWrite,
        requestOptions?: OptimizationsClient.RequestOptions,
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
                "v1/private/optimizations",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.OptimizationWrite.jsonOrThrow(request, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "POST", "/v1/private/optimizations");
    }

    /**
     * 插入或更新优化
     *
     * @param {OpikApi.OptimizationWrite} request
     * @param {OptimizationsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.optimizations.upsertOptimization({
     *         datasetName: "dataset_name",
     *         objectiveName: "objective_name",
     *         status: "running"
     *     })
     */
    public upsertOptimization(
        request: OpikApi.OptimizationWrite,
        requestOptions?: OptimizationsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__upsertOptimization(request, requestOptions));
    }

    private async __upsertOptimization(
        request: OpikApi.OptimizationWrite,
        requestOptions?: OptimizationsClient.RequestOptions,
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
                "v1/private/optimizations",
            ),
            method: "PUT",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.OptimizationWrite.jsonOrThrow(request, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "PUT", "/v1/private/optimizations");
    }

    /**
     * 根据 ID 删除优化
     *
     * @param {OpikApi.DeleteIdsHolder} request
     * @param {OptimizationsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.optimizations.deleteOptimizationsById({
     *         ids: ["ids"]
     *     })
     */
    public deleteOptimizationsById(
        request: OpikApi.DeleteIdsHolder,
        requestOptions?: OptimizationsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__deleteOptimizationsById(request, requestOptions));
    }

    private async __deleteOptimizationsById(
        request: OpikApi.DeleteIdsHolder,
        requestOptions?: OptimizationsClient.RequestOptions,
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
                "v1/private/optimizations/delete",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.DeleteIdsHolder.jsonOrThrow(request, {
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
            "/v1/private/optimizations/delete",
        );
    }

    /**
     * 根据 ID 获取优化
     *
     * @param {string} id
     * @param {OpikApi.GetOptimizationByIdRequest} request
     * @param {OptimizationsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.optimizations.getOptimizationById("id")
     */
    public getOptimizationById(
        id: string,
        request: OpikApi.GetOptimizationByIdRequest = {},
        requestOptions?: OptimizationsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.OptimizationPublic> {
        return core.HttpResponsePromise.fromPromise(this.__getOptimizationById(id, request, requestOptions));
    }

    private async __getOptimizationById(
        id: string,
        _request: OpikApi.GetOptimizationByIdRequest = {},
        requestOptions?: OptimizationsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.OptimizationPublic>> {
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
                `v1/private/optimizations/${core.url.encodePathParam(id)}`,
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
                data: serializers.OptimizationPublic.parseOrThrow(_response.body, {
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
            "/v1/private/optimizations/{id}",
        );
    }

    /**
     * 根据 ID 更新优化
     *
     * @param {string} id
     * @param {OpikApi.OptimizationUpdate} request
     * @param {OptimizationsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.optimizations.updateOptimizationsById("id")
     */
    public updateOptimizationsById(
        id: string,
        request: OpikApi.OptimizationUpdate = {},
        requestOptions?: OptimizationsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__updateOptimizationsById(id, request, requestOptions));
    }

    private async __updateOptimizationsById(
        id: string,
        request: OpikApi.OptimizationUpdate = {},
        requestOptions?: OptimizationsClient.RequestOptions,
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
                `v1/private/optimizations/${core.url.encodePathParam(id)}`,
            ),
            method: "PUT",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.OptimizationUpdate.jsonOrThrow(request, {
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
            "PUT",
            "/v1/private/optimizations/{id}",
        );
    }

    /**
     * 获取用于下载优化日志的预签名 S3 URL
     *
     * @param {string} id
     * @param {OpikApi.GetStudioOptimizationLogsRequest} request
     * @param {OptimizationsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.optimizations.getStudioOptimizationLogs("id")
     */
    public getStudioOptimizationLogs(
        id: string,
        request: OpikApi.GetStudioOptimizationLogsRequest = {},
        requestOptions?: OptimizationsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.OptimizationStudioLog> {
        return core.HttpResponsePromise.fromPromise(this.__getStudioOptimizationLogs(id, request, requestOptions));
    }

    private async __getStudioOptimizationLogs(
        id: string,
        _request: OpikApi.GetStudioOptimizationLogsRequest = {},
        requestOptions?: OptimizationsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.OptimizationStudioLog>> {
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
                `v1/private/optimizations/studio/${core.url.encodePathParam(id)}/logs`,
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
                data: serializers.OptimizationStudioLog.parseOrThrow(_response.body, {
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
            "/v1/private/optimizations/studio/{id}/logs",
        );
    }
}
