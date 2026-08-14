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

export declare namespace WorkspacesClient {
    export type Options = BaseClientOptions;

    export interface RequestOptions extends BaseRequestOptions {}
}

/**
 * 工作区相关资源
 */
export class WorkspacesClient {
    protected readonly _options: NormalizedClientOptions<WorkspacesClient.Options>;

    constructor(options: WorkspacesClient.Options = {}) {
        this._options = normalizeClientOptions(options);
    }

    /**
     * 获取成本汇总
     *
     * @param {OpikApi.WorkspaceMetricsSummaryRequest} request
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.workspaces.costsSummary({
     *         intervalStart: new Date("2024-01-15T09:30:00.000Z"),
     *         intervalEnd: new Date("2024-01-15T09:30:00.000Z")
     *     })
     */
    public costsSummary(
        request: OpikApi.WorkspaceMetricsSummaryRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.Result> {
        return core.HttpResponsePromise.fromPromise(this.__costsSummary(request, requestOptions));
    }

    private async __costsSummary(
        request: OpikApi.WorkspaceMetricsSummaryRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.Result>> {
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
                "v1/private/workspaces/costs/summaries",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.WorkspaceMetricsSummaryRequest.jsonOrThrow(request, {
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
                data: serializers.Result.parseOrThrow(_response.body, {
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
            "POST",
            "/v1/private/workspaces/costs/summaries",
        );
    }

    /**
     * 获取工作区配置
     *
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.workspaces.getWorkspaceConfiguration()
     */
    public getWorkspaceConfiguration(
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.WorkspaceConfiguration> {
        return core.HttpResponsePromise.fromPromise(this.__getWorkspaceConfiguration(requestOptions));
    }

    private async __getWorkspaceConfiguration(
        requestOptions?: WorkspacesClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.WorkspaceConfiguration>> {
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
                "v1/private/workspaces/configurations",
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
                data: serializers.WorkspaceConfiguration.parseOrThrow(_response.body, {
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
            "/v1/private/workspaces/configurations",
        );
    }

    /**
     * 插入或更新工作区配置
     *
     * @param {OpikApi.WorkspaceConfiguration} request
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.UnprocessableEntityError}
     *
     * @example
     *     await client.workspaces.upsertWorkspaceConfiguration({})
     */
    public upsertWorkspaceConfiguration(
        request: OpikApi.WorkspaceConfiguration,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.WorkspaceConfiguration> {
        return core.HttpResponsePromise.fromPromise(this.__upsertWorkspaceConfiguration(request, requestOptions));
    }

    private async __upsertWorkspaceConfiguration(
        request: OpikApi.WorkspaceConfiguration,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.WorkspaceConfiguration>> {
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
                "v1/private/workspaces/configurations",
            ),
            method: "PUT",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.WorkspaceConfiguration.jsonOrThrow(request, {
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
                data: serializers.WorkspaceConfiguration.parseOrThrow(_response.body, {
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
                case 422:
                    throw new OpikApi.UnprocessableEntityError(_response.error.body, _response.rawResponse);
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
            "/v1/private/workspaces/configurations",
        );
    }

    /**
     * 删除工作区配置
     *
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.workspaces.deleteWorkspaceConfiguration()
     */
    public deleteWorkspaceConfiguration(
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__deleteWorkspaceConfiguration(requestOptions));
    }

    private async __deleteWorkspaceConfiguration(
        requestOptions?: WorkspacesClient.RequestOptions,
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
                "v1/private/workspaces/configurations",
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
            "DELETE",
            "/v1/private/workspaces/configurations",
        );
    }

    /**
     * 获取成本每日数据
     *
     * @param {OpikApi.WorkspaceMetricsSummaryRequest} request
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.workspaces.getCost({
     *         intervalStart: new Date("2024-01-15T09:30:00.000Z"),
     *         intervalEnd: new Date("2024-01-15T09:30:00.000Z")
     *     })
     */
    public getCost(
        request: OpikApi.WorkspaceMetricsSummaryRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.WorkspaceMetricResponse> {
        return core.HttpResponsePromise.fromPromise(this.__getCost(request, requestOptions));
    }

    private async __getCost(
        request: OpikApi.WorkspaceMetricsSummaryRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.WorkspaceMetricResponse>> {
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
                "v1/private/workspaces/costs",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.WorkspaceMetricsSummaryRequest.jsonOrThrow(request, {
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
                data: serializers.WorkspaceMetricResponse.parseOrThrow(_response.body, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "POST", "/v1/private/workspaces/costs");
    }

    /**
     * 获取指标每日数据
     *
     * @param {OpikApi.WorkspaceMetricsSummaryRequest} request
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.workspaces.getMetric({
     *         intervalStart: new Date("2024-01-15T09:30:00.000Z"),
     *         intervalEnd: new Date("2024-01-15T09:30:00.000Z")
     *     })
     */
    public getMetric(
        request: OpikApi.WorkspaceMetricsSummaryRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.WorkspaceMetricResponse> {
        return core.HttpResponsePromise.fromPromise(this.__getMetric(request, requestOptions));
    }

    private async __getMetric(
        request: OpikApi.WorkspaceMetricsSummaryRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.WorkspaceMetricResponse>> {
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
                "v1/private/workspaces/metrics",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.WorkspaceMetricsSummaryRequest.jsonOrThrow(request, {
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
                data: serializers.WorkspaceMetricResponse.parseOrThrow(_response.body, {
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
            "POST",
            "/v1/private/workspaces/metrics",
        );
    }

    /**
     * 获取整个工作区聚合的 span 指标时间序列。当 project_ids 为空时，包含工作区中的所有项目；否则只包含给定的项目。
     *
     * @param {OpikApi.WorkspaceSpanMetricRequest} request
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.workspaces.getWorkspaceSpanMetric({
     *         intervalStart: new Date("2024-01-15T09:30:00.000Z")
     *     })
     */
    public getWorkspaceSpanMetric(
        request: OpikApi.WorkspaceSpanMetricRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.WorkspaceMetricResponse> {
        return core.HttpResponsePromise.fromPromise(this.__getWorkspaceSpanMetric(request, requestOptions));
    }

    private async __getWorkspaceSpanMetric(
        request: OpikApi.WorkspaceSpanMetricRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.WorkspaceMetricResponse>> {
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
                "v1/private/workspaces/metrics/spans",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.WorkspaceSpanMetricRequest.jsonOrThrow(request, {
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
                data: serializers.WorkspaceMetricResponse.parseOrThrow(_response.body, {
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
            "POST",
            "/v1/private/workspaces/metrics/spans",
        );
    }

    /**
     * 获取整个工作区聚合的不同 span 令牌用量键名称。当 project_ids 为空时，包含工作区中的所有项目；否则只包含给定的项目。
     *
     * @param {OpikApi.WorkspaceTokenUsageNamesRequest} request
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.workspaces.getWorkspaceTokenUsageNames()
     */
    public getWorkspaceTokenUsageNames(
        request: OpikApi.WorkspaceTokenUsageNamesRequest = {},
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.TokenUsageNames> {
        return core.HttpResponsePromise.fromPromise(this.__getWorkspaceTokenUsageNames(request, requestOptions));
    }

    private async __getWorkspaceTokenUsageNames(
        request: OpikApi.WorkspaceTokenUsageNamesRequest = {},
        requestOptions?: WorkspacesClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.TokenUsageNames>> {
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
                "v1/private/workspaces/token-usage/names",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.WorkspaceTokenUsageNamesRequest.jsonOrThrow(request, {
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
                data: serializers.TokenUsageNames.parseOrThrow(_response.body, {
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
            "POST",
            "/v1/private/workspaces/token-usage/names",
        );
    }

    /**
     * 获取指标汇总
     *
     * @param {OpikApi.WorkspaceMetricsSummaryRequest} request
     * @param {WorkspacesClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.workspaces.metricsSummary({
     *         intervalStart: new Date("2024-01-15T09:30:00.000Z"),
     *         intervalEnd: new Date("2024-01-15T09:30:00.000Z")
     *     })
     */
    public metricsSummary(
        request: OpikApi.WorkspaceMetricsSummaryRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.WorkspaceMetricsSummaryResponse> {
        return core.HttpResponsePromise.fromPromise(this.__metricsSummary(request, requestOptions));
    }

    private async __metricsSummary(
        request: OpikApi.WorkspaceMetricsSummaryRequest,
        requestOptions?: WorkspacesClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.WorkspaceMetricsSummaryResponse>> {
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
                "v1/private/workspaces/metrics/summaries",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.WorkspaceMetricsSummaryRequest.jsonOrThrow(request, {
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
                data: serializers.WorkspaceMetricsSummaryResponse.parseOrThrow(_response.body, {
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
            "POST",
            "/v1/private/workspaces/metrics/summaries",
        );
    }
}
