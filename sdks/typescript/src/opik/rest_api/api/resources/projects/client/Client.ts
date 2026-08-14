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

export declare namespace ProjectsClient {
    export type Options = BaseClientOptions;

    export interface RequestOptions extends BaseRequestOptions {}
}

/**
 * 项目相关资源
 */
export class ProjectsClient {
    protected readonly _options: NormalizedClientOptions<ProjectsClient.Options>;

    constructor(options: ProjectsClient.Options = {}) {
        this._options = normalizeClientOptions(options);
    }

    /**
     * 查找属于某项目的警报
     *
     * @param {string} projectId
     * @param {OpikApi.FindAlertsByProjectRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.projects.findAlertsByProject("projectId")
     */
    public findAlertsByProject(
        projectId: string,
        request: OpikApi.FindAlertsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.AlertPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__findAlertsByProject(projectId, request, requestOptions));
    }

    private async __findAlertsByProject(
        projectId: string,
        request: OpikApi.FindAlertsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.AlertPagePublic>> {
        const { page, size, sorting, filters } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
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
                `v1/private/projects/${core.url.encodePathParam(projectId)}/alerts`,
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
                data: serializers.AlertPagePublic.parseOrThrow(_response.body, {
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
            "/v1/private/projects/{projectId}/alerts",
        );
    }

    /**
     * 查找属于某项目的仪表盘
     *
     * @param {string} projectId
     * @param {OpikApi.FindDashboardsByProjectRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.findDashboardsByProject("projectId")
     */
    public findDashboardsByProject(
        projectId: string,
        request: OpikApi.FindDashboardsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DashboardPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__findDashboardsByProject(projectId, request, requestOptions));
    }

    private async __findDashboardsByProject(
        projectId: string,
        request: OpikApi.FindDashboardsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DashboardPagePublic>> {
        const { page, size, name, sorting, filters } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
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
                `v1/private/projects/${core.url.encodePathParam(projectId)}/dashboards`,
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
                data: serializers.DashboardPagePublic.parseOrThrow(_response.body, {
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
            "/v1/private/projects/{projectId}/dashboards",
        );
    }

    /**
     * 查找属于某项目的数据集
     *
     * @param {string} projectId
     * @param {OpikApi.FindDatasetsByProjectRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.findDatasetsByProject("projectId")
     */
    public findDatasetsByProject(
        projectId: string,
        request: OpikApi.FindDatasetsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.DatasetPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__findDatasetsByProject(projectId, request, requestOptions));
    }

    private async __findDatasetsByProject(
        projectId: string,
        request: OpikApi.FindDatasetsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.DatasetPagePublic>> {
        const { page, size, withExperimentsOnly, withOptimizationsOnly, name, sorting, filters } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            with_experiments_only: withExperimentsOnly,
            with_optimizations_only: withOptimizationsOnly,
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
                `v1/private/projects/${core.url.encodePathParam(projectId)}/datasets`,
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

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/projects/{projectId}/datasets",
        );
    }

    /**
     * 查找属于某项目的实验
     *
     * @param {string} projectId
     * @param {OpikApi.FindExperimentsByProjectRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.projects.findExperimentsByProject("projectId")
     */
    public findExperimentsByProject(
        projectId: string,
        request: OpikApi.FindExperimentsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.ExperimentPagePublic> {
        return core.HttpResponsePromise.fromPromise(
            this.__findExperimentsByProject(projectId, request, requestOptions),
        );
    }

    private async __findExperimentsByProject(
        projectId: string,
        request: OpikApi.FindExperimentsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.ExperimentPagePublic>> {
        const {
            page,
            size,
            datasetId,
            optimizationId,
            types,
            name,
            datasetDeleted,
            sorting,
            filters,
            experimentIds,
            forceSorting,
        } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            datasetId,
            optimization_id: optimizationId,
            types,
            name,
            dataset_deleted: datasetDeleted,
            sorting,
            filters,
            experiment_ids: experimentIds,
            force_sorting: forceSorting,
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
                `v1/private/projects/${core.url.encodePathParam(projectId)}/experiments`,
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
                data: serializers.ExperimentPagePublic.parseOrThrow(_response.body, {
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
            "/v1/private/projects/{projectId}/experiments",
        );
    }

    /**
     * 查找属于某项目的优化
     *
     * @param {string} projectId
     * @param {OpikApi.FindOptimizationsByProjectRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.projects.findOptimizationsByProject("projectId")
     */
    public findOptimizationsByProject(
        projectId: string,
        request: OpikApi.FindOptimizationsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.OptimizationPagePublic> {
        return core.HttpResponsePromise.fromPromise(
            this.__findOptimizationsByProject(projectId, request, requestOptions),
        );
    }

    private async __findOptimizationsByProject(
        projectId: string,
        request: OpikApi.FindOptimizationsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.OptimizationPagePublic>> {
        const { page, size, datasetId, datasetName, name, datasetDeleted, filters } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            dataset_id: datasetId,
            dataset_name: datasetName,
            name,
            dataset_deleted: datasetDeleted,
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
                `v1/private/projects/${core.url.encodePathParam(projectId)}/optimizations`,
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

        return handleNonStatusCodeError(
            _response.error,
            _response.rawResponse,
            "GET",
            "/v1/private/projects/{projectId}/optimizations",
        );
    }

    /**
     * 获取属于某项目的提示词
     *
     * @param {string} projectId
     * @param {OpikApi.GetPromptsByProjectRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.getPromptsByProject("projectId")
     */
    public getPromptsByProject(
        projectId: string,
        request: OpikApi.GetPromptsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.PromptPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__getPromptsByProject(projectId, request, requestOptions));
    }

    private async __getPromptsByProject(
        projectId: string,
        request: OpikApi.GetPromptsByProjectRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.PromptPagePublic>> {
        const { page, size, name, sorting, filters } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
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
                `v1/private/projects/${core.url.encodePathParam(projectId)}/prompts`,
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
                data: serializers.PromptPagePublic.parseOrThrow(_response.body, {
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
            "/v1/private/projects/{projectId}/prompts",
        );
    }

    /**
     * 查找项目
     *
     * @param {OpikApi.FindProjectsRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.findProjects()
     */
    public findProjects(
        request: OpikApi.FindProjectsRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.ProjectPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__findProjects(request, requestOptions));
    }

    private async __findProjects(
        request: OpikApi.FindProjectsRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.ProjectPagePublic>> {
        const { page, size, name, sorting } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            name,
            sorting,
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
                "v1/private/projects",
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
                data: serializers.ProjectPagePublic.parseOrThrow(_response.body, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "GET", "/v1/private/projects");
    }

    /**
     * 创建项目
     *
     * @param {OpikApi.ProjectWrite} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.UnprocessableEntityError}
     *
     * @example
     *     await client.projects.createProject({
     *         name: "name"
     *     })
     */
    public createProject(
        request: OpikApi.ProjectWrite,
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__createProject(request, requestOptions));
    }

    private async __createProject(
        request: OpikApi.ProjectWrite,
        requestOptions?: ProjectsClient.RequestOptions,
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
                "v1/private/projects",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.ProjectWrite.jsonOrThrow(request, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "POST", "/v1/private/projects");
    }

    /**
     * 根据 ID 获取项目
     *
     * @param {string} id
     * @param {OpikApi.GetProjectByIdRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.getProjectById("id")
     */
    public getProjectById(
        id: string,
        request: OpikApi.GetProjectByIdRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.ProjectPublic> {
        return core.HttpResponsePromise.fromPromise(this.__getProjectById(id, request, requestOptions));
    }

    private async __getProjectById(
        id: string,
        _request: OpikApi.GetProjectByIdRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.ProjectPublic>> {
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
                `v1/private/projects/${core.url.encodePathParam(id)}`,
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
                data: serializers.ProjectPublic.parseOrThrow(_response.body, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "GET", "/v1/private/projects/{id}");
    }

    /**
     * 根据 ID 删除项目
     *
     * @param {string} id
     * @param {OpikApi.DeleteProjectByIdRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.ConflictError}
     *
     * @example
     *     await client.projects.deleteProjectById("id")
     */
    public deleteProjectById(
        id: string,
        request: OpikApi.DeleteProjectByIdRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__deleteProjectById(id, request, requestOptions));
    }

    private async __deleteProjectById(
        id: string,
        _request: OpikApi.DeleteProjectByIdRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
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
                `v1/private/projects/${core.url.encodePathParam(id)}`,
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "DELETE", "/v1/private/projects/{id}");
    }

    /**
     * 根据 ID 更新项目
     *
     * @param {string} id
     * @param {OpikApi.ProjectUpdate} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.UnprocessableEntityError}
     *
     * @example
     *     await client.projects.updateProject("id")
     */
    public updateProject(
        id: string,
        request: OpikApi.ProjectUpdate = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__updateProject(id, request, requestOptions));
    }

    private async __updateProject(
        id: string,
        request: OpikApi.ProjectUpdate = {},
        requestOptions?: ProjectsClient.RequestOptions,
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
                `v1/private/projects/${core.url.encodePathParam(id)}`,
            ),
            method: "PATCH",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.ProjectUpdate.jsonOrThrow(request, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "PATCH", "/v1/private/projects/{id}");
    }

    /**
     * 批量删除项目
     *
     * @param {OpikApi.BatchDelete} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.deleteProjectsBatch({
     *         ids: ["ids"]
     *     })
     */
    public deleteProjectsBatch(
        request: OpikApi.BatchDelete,
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<void> {
        return core.HttpResponsePromise.fromPromise(this.__deleteProjectsBatch(request, requestOptions));
    }

    private async __deleteProjectsBatch(
        request: OpikApi.BatchDelete,
        requestOptions?: ProjectsClient.RequestOptions,
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
                "v1/private/projects/delete",
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "POST", "/v1/private/projects/delete");
    }

    /**
     * 根据项目 ID 查找反馈分数名称
     *
     * @param {OpikApi.FindFeedbackScoreNamesByProjectIdsRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.findFeedbackScoreNamesByProjectIds()
     */
    public findFeedbackScoreNamesByProjectIds(
        request: OpikApi.FindFeedbackScoreNamesByProjectIdsRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.FeedbackScoreNames> {
        return core.HttpResponsePromise.fromPromise(this.__findFeedbackScoreNamesByProjectIds(request, requestOptions));
    }

    private async __findFeedbackScoreNamesByProjectIds(
        request: OpikApi.FindFeedbackScoreNamesByProjectIdsRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.FeedbackScoreNames>> {
        const { projectIds } = request;
        const _queryParams: Record<string, unknown> = {
            project_ids: projectIds,
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
                "v1/private/projects/feedback-scores/names",
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
                data: serializers.FeedbackScoreNames.parseOrThrow(_response.body, {
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
            "/v1/private/projects/feedback-scores/names",
        );
    }

    /**
     * 查找令牌用量名称
     *
     * @param {string} id
     * @param {OpikApi.FindTokenUsageNamesRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.findTokenUsageNames("id")
     */
    public findTokenUsageNames(
        id: string,
        request: OpikApi.FindTokenUsageNamesRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.TokenUsageNames> {
        return core.HttpResponsePromise.fromPromise(this.__findTokenUsageNames(id, request, requestOptions));
    }

    private async __findTokenUsageNames(
        id: string,
        _request: OpikApi.FindTokenUsageNamesRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
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
                `v1/private/projects/${core.url.encodePathParam(id)}/token-usage/names`,
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
            "/v1/private/projects/{id}/token-usage/names",
        );
    }

    /**
     * 获取项目的 KPI 卡片指标
     *
     * @param {string} id
     * @param {OpikApi.KpiCardRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     *
     * @example
     *     await client.projects.getProjectKpiCards("id", {
     *         entityType: "traces",
     *         intervalStart: new Date("2024-01-15T09:30:00.000Z")
     *     })
     */
    public getProjectKpiCards(
        id: string,
        request: OpikApi.KpiCardRequest,
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.KpiCardResponse> {
        return core.HttpResponsePromise.fromPromise(this.__getProjectKpiCards(id, request, requestOptions));
    }

    private async __getProjectKpiCards(
        id: string,
        request: OpikApi.KpiCardRequest,
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.KpiCardResponse>> {
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
                `v1/private/projects/${core.url.encodePathParam(id)}/kpi-cards`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.KpiCardRequest.jsonOrThrow(request, {
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
                data: serializers.KpiCardResponse.parseOrThrow(_response.body, {
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
            "/v1/private/projects/{id}/kpi-cards",
        );
    }

    /**
     * 获取项目的指定指标
     *
     * @param {string} id
     * @param {OpikApi.ProjectMetricRequestPublic} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.NotFoundError}
     *
     * @example
     *     await client.projects.getProjectMetrics("id")
     */
    public getProjectMetrics(
        id: string,
        request: OpikApi.ProjectMetricRequestPublic = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.ProjectMetricResponsePublic> {
        return core.HttpResponsePromise.fromPromise(this.__getProjectMetrics(id, request, requestOptions));
    }

    private async __getProjectMetrics(
        id: string,
        request: OpikApi.ProjectMetricRequestPublic = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.ProjectMetricResponsePublic>> {
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
                `v1/private/projects/${core.url.encodePathParam(id)}/metrics`,
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.ProjectMetricRequestPublic.jsonOrThrow(request, {
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
                data: serializers.ProjectMetricResponsePublic.parseOrThrow(_response.body, {
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
            "/v1/private/projects/{id}/metrics",
        );
    }

    /**
     * 获取项目统计信息
     *
     * @param {OpikApi.GetProjectStatsRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @example
     *     await client.projects.getProjectStats()
     */
    public getProjectStats(
        request: OpikApi.GetProjectStatsRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.ProjectStatsSummary> {
        return core.HttpResponsePromise.fromPromise(this.__getProjectStats(request, requestOptions));
    }

    private async __getProjectStats(
        request: OpikApi.GetProjectStatsRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.ProjectStatsSummary>> {
        const { page, size, name, filters, fromTime, toTime, sorting } = request;
        const _queryParams: Record<string, unknown> = {
            page,
            size,
            name,
            filters,
            from_time: fromTime?.toISOString(),
            to_time: toTime?.toISOString(),
            sorting,
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
                "v1/private/projects/stats",
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
                data: serializers.ProjectStatsSummary.parseOrThrow(_response.body, {
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

        return handleNonStatusCodeError(_response.error, _response.rawResponse, "GET", "/v1/private/projects/stats");
    }

    /**
     * 获取项目
     *
     * @param {OpikApi.ProjectRetrieveDetailed} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.NotFoundError}
     * @throws {@link OpikApi.UnprocessableEntityError}
     *
     * @example
     *     await client.projects.retrieveProject({
     *         name: "name"
     *     })
     */
    public retrieveProject(
        request: OpikApi.ProjectRetrieveDetailed,
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.ProjectDetailed> {
        return core.HttpResponsePromise.fromPromise(this.__retrieveProject(request, requestOptions));
    }

    private async __retrieveProject(
        request: OpikApi.ProjectRetrieveDetailed,
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.ProjectDetailed>> {
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
                "v1/private/projects/retrieve",
            ),
            method: "POST",
            headers: _headers,
            contentType: "application/json",
            queryParameters: requestOptions?.queryParams,
            requestType: "json",
            body: serializers.ProjectRetrieveDetailed.jsonOrThrow(request, {
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
                data: serializers.ProjectDetailed.parseOrThrow(_response.body, {
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
            "POST",
            "/v1/private/projects/retrieve",
        );
    }

    /**
     * 返回项目所有实体类型中最近的活动条目，按日期降序排列。
     *
     * @param {string} projectId
     * @param {OpikApi.GetRecentActivityRequest} request
     * @param {ProjectsClient.RequestOptions} requestOptions - 请求特定的配置。
     *
     * @throws {@link OpikApi.BadRequestError}
     * @throws {@link OpikApi.InternalServerError}
     *
     * @example
     *     await client.projects.getRecentActivity("projectId")
     */
    public getRecentActivity(
        projectId: string,
        request: OpikApi.GetRecentActivityRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): core.HttpResponsePromise<OpikApi.RecentActivityPagePublic> {
        return core.HttpResponsePromise.fromPromise(this.__getRecentActivity(projectId, request, requestOptions));
    }

    private async __getRecentActivity(
        projectId: string,
        request: OpikApi.GetRecentActivityRequest = {},
        requestOptions?: ProjectsClient.RequestOptions,
    ): Promise<core.WithRawResponse<OpikApi.RecentActivityPagePublic>> {
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
                `v1/private/projects/${core.url.encodePathParam(projectId)}/activities`,
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
                data: serializers.RecentActivityPagePublic.parseOrThrow(_response.body, {
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
                case 500:
                    throw new OpikApi.InternalServerError(_response.error.body, _response.rawResponse);
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
            "/v1/private/projects/{projectId}/activities",
        );
    }
}
