package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Visibility;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;
import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_WORKSPACE;
import static com.comet.opik.api.ReactServiceErrorResponse.NOT_ALLOWED_TO_ACCESS_WORKSPACE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_QUERY_PARAM;

@RequiredArgsConstructor
@Slf4j
class RemoteAuthService implements AuthService {
    private static final String USER_NOT_FOUND = "User not found";
    private static final String NOT_LOGGED_USER = "Please login first";

    // 远程错误响应体是任意的上游内容，因此要限制进入日志的量。
    private static final int MAX_LOGGED_BODY_LENGTH = 512;

    // GenericType 实例是线程安全的且构建代价高，因此复用一个实例。
    private static final GenericType<List<WorkspaceIdNameResponse>> WORKSPACE_LIST_TYPE = new GenericType<>() {
    };

    private static final Map<String, Set<String>> PUBLIC_ENDPOINTS = new HashMap<>() {
        {
            // 私有项目相关端点
            put("^/v1/private/projects/?$", Set.of("GET"));
            put("^/v1/private/projects/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/projects/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/metrics/?$",
                    Set.of("POST"));
            put("^/v1/private/projects/retrieve/?$", Set.of("POST"));
            put("^/v1/private/spans/?$", Set.of("GET"));
            put("^/v1/private/spans/stats/?$", Set.of("GET"));
            put("^/v1/private/spans/feedback-scores/names/?$", Set.of("GET"));
            put("^/v1/private/spans/search/?$", Set.of("POST"));
            put("^/v1/private/traces/?$", Set.of("GET"));
            put("^/v1/private/traces/stats/?$", Set.of("GET"));
            put("^/v1/private/traces/feedback-scores/names/?$", Set.of("GET"));
            put("^/v1/private/traces/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/traces/threads/?$", Set.of("GET"));
            put("^/v1/private/traces/threads/retrieve/?$", Set.of("POST"));
            put("^/v1/private/traces/search/?$", Set.of("POST"));

            // 公共数据集相关端点
            put("^/v1/private/datasets/?$", Set.of("GET"));
            put("^/v1/private/datasets/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/datasets/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/items/?$",
                    Set.of("GET"));
            put("^/v1/private/datasets/items/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$",
                    Set.of("GET"));
            put("^/v1/private/datasets/retrieve/?$", Set.of("POST"));
            put("^/v1/private/datasets/items/stream/?$", Set.of("POST"));
        }
    };

    private final @NonNull Client client;
    private final @NonNull AuthenticationConfig.UrlConfig reactServiceUrl;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull CacheService cacheService;

    @Builder(toBuilder = true)
    record AuthRequest(String workspaceName, String path,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> requiredPermissions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Builder(toBuilder = true)
    record AuthResponse(
            String user, String workspaceId, String workspaceName, List<Quota> quotas) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkspaceIdNameResponse(String workspaceId, String workspaceName) {
    }

    @Builder(toBuilder = true)
    record ValidatedAuthCredentials(
            boolean shouldCache,
            String userName,
            String workspaceId,
            String workspaceName,
            List<Quota> quotas) {

        static ValidatedAuthCredentials from(AuthResponse authResponse) {
            return ValidatedAuthCredentials.builder()
                    .shouldCache(true)
                    .userName(authResponse.user())
                    .workspaceId(authResponse.workspaceId())
                    .workspaceName(authResponse.workspaceName())
                    .quotas(authResponse.quotas())
                    .build();
        }

        static ValidatedAuthCredentials from(CacheService.AuthCredentials authCredentials) {
            return ValidatedAuthCredentials.builder()
                    .shouldCache(false)
                    .userName(authCredentials.userName())
                    .workspaceId(authCredentials.workspaceId())
                    .workspaceName(authCredentials.workspaceName())
                    .quotas(authCredentials.quotas())
                    .build();
        }

        CacheService.AuthCredentials toAuthCredentials() {
            return CacheService.AuthCredentials.builder()
                    .userName(userName)
                    .workspaceId(workspaceId)
                    .workspaceName(workspaceName)
                    .quotas(quotas)
                    .build();
        }
    }

    @Override
    public void authenticate(HttpHeaders headers, Cookie sessionToken, ContextInfoHolder contextInfo) {
        UriInfo uriInfo = contextInfo.uriInfo();
        String path = uriInfo.getRequestUri().getPath();
        var currentWorkspaceName = Optional.ofNullable(headers.getHeaderString(RequestContext.WORKSPACE_HEADER))
                .orElseGet(() -> uriInfo.getQueryParameters().getFirst(WORKSPACE_QUERY_PARAM));
        if (StringUtils.isBlank(currentWorkspaceName)) {
            log.warn("缺少工作区名称");
            throw new ClientErrorException(MISSING_WORKSPACE, Response.Status.FORBIDDEN);
        }

        List<String> requiredPermissions = contextInfo.requiredPermissions();

        try {
            if (sessionToken != null) {
                authenticateUsingSessionToken(sessionToken, currentWorkspaceName, path, requiredPermissions);
            } else {
                authenticateUsingApiKey(headers, currentWorkspaceName, path, requiredPermissions);
            }
        } catch (ClientErrorException authException) {
            if (!isDefaultWorkspace(currentWorkspaceName) && isNotAuthenticated(authException)
                    && isEndpointPublic(contextInfo)) {
                log.info("对端点使用 PUBLIC 可见性: {}", path);
                String workspaceId = getWorkspaceId(currentWorkspaceName);
                requestContext.get().setWorkspaceId(workspaceId);
                requestContext.get().setWorkspaceName(currentWorkspaceName);
                requestContext.get().setVisibility(Visibility.PUBLIC);
                requestContext.get().setUserName("Public");
                return;
            }
            throw authException;
        }
    }

    @Override
    public void authenticateSession(Cookie sessionToken) {
        if (sessionToken == null || StringUtils.isBlank(sessionToken.getValue())) {
            log.info("未找到 cookie");
            throw new ClientErrorException(NOT_LOGGED_USER, Response.Status.FORBIDDEN);
        }
    }

    @Override
    public List<WorkspaceInfo> listEligibleWorkspaces(Cookie sessionToken) {
        requireSession(sessionToken);
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("workspaces")
                .queryParam("withoutExtendedData", true)
                .request()
                .accept(MediaType.APPLICATION_JSON)
                // 在巨大工作区列表的情况下避免 gzip 双重解压问题
                .acceptEncoding("identity")
                .cookie(sessionToken)
                .get()) {
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw toSessionAuthException(response);
            }
            return response.readEntity(WORKSPACE_LIST_TYPE).stream()
                    .filter(workspace -> !isDefaultWorkspace(workspace.workspaceName()))
                    .map(workspace -> WorkspaceInfo.builder()
                            .id(workspace.workspaceId())
                            .name(workspace.workspaceName())
                            .build())
                    .toList();
        }
    }

    @Override
    public void authorizeOAuth(@NonNull ValidatedToken token, @NonNull ContextInfoHolder contextInfo) {
        String path = contextInfo.uriInfo().getRequestUri().getPath();
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path("auth-by-username")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                // 避免 gzip 双重解压问题，与 listEligibleWorkspaces 中相同
                .acceptEncoding("identity")
                .header(OAUTH_USERNAME_HEADER, token.userName())
                .post(Entity.json(AuthRequest.builder()
                        .workspaceName(token.workspaceName())
                        .path(path)
                        .requiredPermissions(contextInfo.requiredPermissions())
                        .build()))) {
            var authResponse = verifyResponse(response);
            var credentials = ValidatedAuthCredentials.from(authResponse);
            setCredentialIntoContext(credentials, token.workspaceName(), null);
        }
    }

    @Override
    public UserWorkspace authorizeWorkspace(Cookie sessionToken, @NonNull String workspaceName) {
        requireSession(sessionToken);
        if (isDefaultWorkspace(workspaceName)) {
            throw new ClientErrorException(NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.FORBIDDEN);
        }
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path("auth-session")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                // 避免 gzip 双重解压问题，与 listEligibleWorkspaces 中相同
                .acceptEncoding("identity")
                .cookie(sessionToken)
                .post(Entity.json(AuthRequest.builder().workspaceName(workspaceName).build()))) {
            var authResponse = verifyResponse(response);
            return UserWorkspace.builder()
                    .userName(authResponse.user())
                    .workspaceId(authResponse.workspaceId())
                    .workspaceName(authResponse.workspaceName())
                    .build();
        }
    }

    private void requireSession(Cookie sessionToken) {
        if (sessionToken == null || StringUtils.isBlank(sessionToken.getValue())) {
            throw new ClientErrorException(NOT_LOGGED_USER, Response.Status.FORBIDDEN);
        }
    }

    private ClientErrorException toSessionAuthException(Response response) {
        if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()
                || response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            return new ClientErrorException(NOT_LOGGED_USER, Response.Status.FORBIDDEN);
        }
        throw unexpectedRemoteError("listing workspaces", response);
    }

    /**
     * 记录远程响应（状态码和尽力而为的响应体，用于生产环境调试）并构建一个
     * {@link InternalServerErrorException}，携带标识失败操作的自定义消息。响应体只是
     * 被记录日志，绝不会暴露给调用方，因此任何内部/远程细节都不会冒泡到端点。
     */
    private InternalServerErrorException unexpectedRemoteError(String operation, Response response) {
        log.error("在 {} 时发生意外错误，收到的状态码: {}，响应体: '{}'",
                operation, response.getStatus(), readBodySafely(response));
        return new InternalServerErrorException("Unexpected error while " + operation);
    }

    /**
     * 仅为诊断而读取响应体，绝不会暴露给调用方。结果被截断到
     * {@link #MAX_LOGGED_BODY_LENGTH} 个字符：远程错误响应体是任意的上游内容（代理错误页
     * 或堆栈跟踪，不一定是我们自己的 JSON），因此绝不能让它淹没日志或把无界的
     * 上游细节带进日志。
     */
    private static String readBodySafely(Response response) {
        try {
            if (!isEntityReadable(response)) {
                return "";
            }
            return StringUtils.abbreviate(response.readEntity(String.class), MAX_LOGGED_BODY_LENGTH);
        } catch (RuntimeException e) {
            log.warn("读取远程响应体用于调试时失败", e);
            return "";
        }
    }

    /**
     * 守护一次 {@code readEntity} 调用：报告响应是否携带实体、以及该实体是否已被缓冲，
     * 从而可以被读取、并再次读取。客户端响应实体由一次性输入流支撑，因此如果不缓冲，
     * 第一个读取者会消耗掉它，之后每次读取都会失败。缓冲是幂等的 —— 已经缓冲过的
     * 实体会报告成功 —— 因此调用方无需跟踪它是否已经发生过。
     *
     * @return 当没有实体、或实体无法缓冲从而读取不安全时返回 {@code false}
     */
    private static boolean isEntityReadable(Response response) {
        if (!response.hasEntity()) {
            return false;
        }
        try {
            return response.bufferEntity();
        } catch (RuntimeException e) {
            log.warn("缓冲远程响应实体失败，状态: '{}'", response.getStatus(), e);
            return false;
        }
    }

    /**
     * 报告响应体是否应作为 JSON 读取，依据的是注册的 Jackson provider 实际会解析的内容，
     * 而不仅仅是精确的 {@code application/json} 类型。结构化后缀（{@code application/problem+json}）
     * 和非 {@code application} 类型（{@code text/json}）都能正常反序列化，因此用
     * {@code APPLICATION_JSON_TYPE.isCompatible} 来做门控会丢弃一条完全可用的远程消息。通配符或缺失的
     * 类型不能告诉我们关于响应体的任何信息，也不会被当作 JSON —— 包括携带后缀的通配符
     * （{@code application/*+json}），它本来就不是合法的响应内容类型。
     */
    private static boolean isJson(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        var subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        if (subtype.contains(MediaType.MEDIA_TYPE_WILDCARD)) {
            return false;
        }
        return "json".equals(subtype) || subtype.endsWith("+json");
    }

    /**
     * 在不假设响应体是 JSON 的前提下，从非成功的 react-service 响应中提取错误消息。
     * <p>
     * react 服务并不总是以 {@link ReactServiceErrorResponse} 应答：由 Dropwizard 的
     * {@code @Auth} 过滤器守护的端点（例如 {@code /opik/auth-session}）会通过默认的
     * {@code UnauthorizedHandler} 拒绝过期或无效的会话 cookie，后者以 {@code text/plain} 响应体
     * 返回 {@code 401}。把这样的响应当作 {@link ReactServiceErrorResponse} 读取会让 Jersey 抛出
     * {@code MessageBodyProviderNotFoundException}，这是一个不是 {@link ClientErrorException} 的
     * {@code ProcessingException}，因此会逃过认证过滤器并以 {@code 500} 而不是预期的
     * 客户端错误暴露出来。
     * <p>
     * 只有 JSON 响应体才被视为面向调用方的消息。任何其他内容类型都是框架级的
     * 响应而非应用错误，因此会被记录日志，并使用面向调用方的 {@code fallback} 而不是
     * 泄漏远程框架的措辞。每次读取都由 {@link #isEntityReadable(Response)} 守护，它
     * 同时也会缓冲，从而让这里的诊断日志和同一响应的任何后续读取者都能读取它，
     * 而不是依赖成为第一个消费一次性实体流的人。
     *
     * @param fallback 当响应体缺失、不是 JSON、不可读或为空时使用的消息
     */
    private static String readErrorMessage(Response response, String fallback) {
        if (!isEntityReadable(response)) {
            return fallback;
        }
        var mediaType = response.getMediaType();
        if (!isJson(mediaType)) {
            log.warn("React 服务回复了非 JSON 错误体，状态: '{}'，contentType: '{}'，响应体: '{}'",
                    response.getStatus(), mediaType, readBodySafely(response));
            return fallback;
        }
        try {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            return errorResponse == null || StringUtils.isBlank(errorResponse.msg())
                    ? fallback
                    : errorResponse.msg().strip();
        } catch (RuntimeException e) {
            log.warn("读取 react 服务错误响应失败，状态: '{}'，响应体: '{}'",
                    response.getStatus(), readBodySafely(response), e);
            return fallback;
        }
    }

    private void authenticateUsingSessionToken(Cookie sessionToken, String workspaceName, String path,
            List<String> requiredPermissions) {
        if (isDefaultWorkspace(workspaceName)) {
            log.warn("UI 认证不允许默认工作区名称");
            throw new ClientErrorException(
                    NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.FORBIDDEN);
        }
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path("auth-session")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                // 避免 gzip 双重解压问题，与 listEligibleWorkspaces 中相同
                .acceptEncoding("identity")
                .cookie(sessionToken)
                .post(Entity.json(AuthRequest.builder()
                        .workspaceName(workspaceName)
                        .path(path)
                        .requiredPermissions(requiredPermissions)
                        .build()))) {
            var authResponse = verifyResponse(response);
            var credentials = ValidatedAuthCredentials.from(authResponse);
            setCredentialIntoContext(credentials, workspaceName, sessionToken.getValue());
        }
    }

    private void authenticateUsingApiKey(HttpHeaders headers, String workspaceName, String path,
            List<String> requiredPermissions) {
        var apiKey = Optional.ofNullable(headers.getHeaderString(HttpHeaders.AUTHORIZATION)).orElse("");
        if (apiKey.isBlank()) {
            log.info("请求头中未找到 API key");
            throw new ClientErrorException(MISSING_API_KEY, Response.Status.UNAUTHORIZED);
        }
        var credentials = validateApiKeyAndGetCredentials(workspaceName, apiKey, path, requiredPermissions);
        if (credentials.shouldCache()) {
            log.debug("正在缓存 API key 对应的用户和工作区 id");
            cacheService.cache(apiKey, workspaceName, requiredPermissions, credentials.toAuthCredentials());
        }
        setCredentialIntoContext(credentials, workspaceName, apiKey);
    }

    private ValidatedAuthCredentials validateApiKeyAndGetCredentials(String workspaceName, String apiKey, String path,
            List<String> requiredPermissions) {
        var credentials = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName,
                requiredPermissions);
        if (credentials.isEmpty()) {
            log.debug("缓存中未找到 API key 对应的用户和工作区 id");
            try (var response = client.target(URI.create(reactServiceUrl.url()))
                    .path("opik")
                    .path("auth")
                    .request()
                    .accept(MediaType.APPLICATION_JSON)
                    // 避免 gzip 双重解压问题，与 listEligibleWorkspaces 中相同
                    .acceptEncoding("identity")
                    .header(HttpHeaders.AUTHORIZATION,
                            apiKey)
                    .post(Entity.json(AuthRequest.builder()
                            .workspaceName(workspaceName)
                            .path(path)
                            .requiredPermissions(requiredPermissions)
                            .build()))) {
                var authResponse = verifyResponse(response);
                return ValidatedAuthCredentials.from(authResponse);
            }
        } else {
            return ValidatedAuthCredentials.from(credentials.get());
        }
    }

    private AuthResponse verifyResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            var authResponse = response.readEntity(AuthResponse.class);
            if (StringUtils.isEmpty(authResponse.user())) {
                log.warn("未找到用户");
                throw new ClientErrorException(USER_NOT_FOUND, Response.Status.UNAUTHORIZED);
            }
            return authResponse;
        } else if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()) {
            throw new ClientErrorException(readErrorMessage(response, NOT_LOGGED_USER),
                    Response.Status.UNAUTHORIZED);
        } else if (response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            // EM 目前从不返回 FORBIDDEN
            throw new ClientErrorException(
                    NOT_ALLOWED_TO_ACCESS_WORKSPACE, Response.Status.FORBIDDEN);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            throw new ClientErrorException(readErrorMessage(response, MISSING_WORKSPACE),
                    Response.Status.BAD_REQUEST);
        }
        throw unexpectedRemoteError("authenticating user", response);
    }

    private void setCredentialIntoContext(
            ValidatedAuthCredentials credentials, String fallbackWorkspaceName, String apiKey) {
        var workspaceName = Optional.ofNullable(credentials.workspaceName()).orElse(fallbackWorkspaceName);
        log.debug(
                "正在将凭证设置到上下文中，userName: '{}'，workspaceId: '{}'，workspaceName: '{}'，quotas: '{}'",
                credentials.userName(), credentials.workspaceId(), workspaceName, credentials.quotas());
        requestContext.get().setUserName(credentials.userName());
        requestContext.get().setWorkspaceId(credentials.workspaceId());
        requestContext.get().setWorkspaceName(workspaceName);
        requestContext.get().setQuotas(credentials.quotas());
        requestContext.get().setApiKey(apiKey);
    }

    private boolean isEndpointPublic(ContextInfoHolder contextInfo) {
        for (String pattern : PUBLIC_ENDPOINTS.keySet()) {
            if (contextInfo.uriInfo().getRequestUri().getPath().matches(pattern)) {
                Set<String> allowedMethods = PUBLIC_ENDPOINTS.get(pattern);
                if (allowedMethods.contains(contextInfo.method())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNotAuthenticated(ClientErrorException authException) {
        int status = authException.getResponse().getStatus();
        return status == Response.Status.UNAUTHORIZED.getStatusCode()
                || status == Response.Status.FORBIDDEN.getStatusCode();
    }

    private boolean isDefaultWorkspace(String workspaceName) {
        return ProjectService.DEFAULT_WORKSPACE_NAME.equalsIgnoreCase(workspaceName);
    }

    private String getWorkspaceId(String workspaceName) {
        try (var response = client.target(URI.create(reactServiceUrl.url()))
                .path("workspaces")
                .path("workspace-id")
                .queryParam("name", workspaceName)
                .request()
                // 避免 gzip 双重解压问题，与 listEligibleWorkspaces 中相同
                .acceptEncoding("identity")
                .get()) {

            return getWorkspaceIdFromResponse(response);
        }
    }

    private String getWorkspaceIdFromResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(String.class);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            var message = readErrorMessage(response, MISSING_WORKSPACE);
            // 在公共端点回退路径上，未知的工作区名称是调用方的错误，而非服务器故障。
            log.warn("按名称未找到工作区: '{}'", message);
            throw new ClientErrorException(message, Response.Status.BAD_REQUEST);
        }

        throw unexpectedRemoteError("getting workspace id", response);
    }
}
