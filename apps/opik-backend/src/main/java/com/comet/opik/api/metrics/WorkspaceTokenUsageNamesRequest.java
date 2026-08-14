package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * 跨项目获取去重的 span token 用量键名称的请求。当 {@code projectIds} 为空时，服务会在查询前
 * 将其解析为工作区中的每个项目；否则只使用给定的项目。该请求与单项目
 * {@code GET /v1/private/projects/{id}/token-usage/names} 端点保持一致，并扩展到项目集合。
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkspaceTokenUsageNamesRequest(Set<@NotNull UUID> projectIds) {
}
