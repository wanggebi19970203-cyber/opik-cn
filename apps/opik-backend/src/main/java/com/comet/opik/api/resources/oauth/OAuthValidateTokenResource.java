package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_VALIDATE_TOKEN_RESOURCE_BASE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_TYPE_BEARER;

@Path(OAUTH_VALIDATE_TOKEN_RESOURCE_BASE_PATH)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 授权服务器资源")
public class OAuthValidateTokenResource {

    private final @NonNull McpOAuthService mcpOAuthService;

    @POST
    @Operation(operationId = "validateOAuthToken", summary = "验证OAuth访问令牌", description = "内省Bearer访问令牌并返回其解析的身份", responses = {
            @ApiResponse(responseCode = "200", description = "已验证的令牌身份", content = @Content(schema = @Schema(implementation = ValidatedToken.class))),
            @ApiResponse(responseCode = "401", description = "缺失、格式错误或未知的访问令牌")})
    public Response validate(@HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader) {
        if (!McpOAuthTokenUtils.isMcpOAuthToken(authHeader)) {
            log.info("MCP OAuth验证被拒绝：未提供Bearer访问令牌");
            throw new NotAuthorizedException(TOKEN_TYPE_BEARER);
        }

        String token = McpOAuthTokenUtils.extractBearerToken(authHeader);
        ValidatedToken validated = mcpOAuthService.validateAccessToken(token)
                .orElseThrow(() -> {
                    log.info("MCP OAuth验证被拒绝：令牌未激活");
                    return new NotAuthorizedException(TOKEN_TYPE_BEARER);
                });

        log.info("MCP OAuth验证成功");
        return Response.ok(validated).build();
    }
}
