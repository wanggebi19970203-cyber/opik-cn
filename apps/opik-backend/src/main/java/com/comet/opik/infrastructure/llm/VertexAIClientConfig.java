package com.comet.opik.infrastructure.llm;

import com.google.cloud.vertexai.Transport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.Map;

/**
 * Vertex AI 客户端的配置。
 * <p>
 * {@code multiRegionApiEndpoints} 把多区域位置映射到服务于它的主机。SDK 从位置推导主机
 * 为 {@code %s-aiplatform.googleapis.com}，这只对单区域位置成立，因此
 * 多区域位置必须在这里列出，否则客户端会指向一个不存在的名称（例如
 * {@code global-aiplatform.googleapis.com}）。单区域位置被有意省略，保留 SDK 默认值。
 * <p>
 * 该映射是强制的，在代码中没有对应物：配置文件是定义这些主机的唯一位置，
 * 因此运维在那里读到什么，客户端就使用什么。
 * <p>
 * 位置的查找是规范化（去除空白并小写化）的，因此键上有此模式：配置成
 * {@code Global:} 的键永远不会被匹配到，并会静默回退到推导出的主机，所以它会
 * 在启动时被拒绝。
 * <p>
 * 值是主机，不是 URL —— SDK 接收裸主机并在 gRPC 传输上自己追加 {@code :443} ——
 * 因此不能使用 {@code @URL}：它会拒绝这里随附的每个主机。下面的模式是主机的等效约束，
 * 它能捕获粘贴 {@code https://aiplatform.googleapis.com} 这一现实错误，否则该错误
 * 只会表现为一次失败的补全。端口和末尾斜杠被接受是因为 SDK 接受
 * 它们，这正是测试能把每个位置指向本地桩的原因。
 */
@Builder(toBuilder = true)
public record VertexAIClientConfig(
        @NotBlank String scope,
        @NotEmpty Map<@Pattern(regexp = "[a-z0-9-]+", message = "must be a lower-case location such as 'global'") String, //
                @NotBlank @Pattern(regexp = "[A-Za-z0-9.-]+(:\\d+)?/?", message = "must be a host such as 'aiplatform.googleapis.com', with no scheme or path") String> multiRegionApiEndpoints,
        @NotNull Transport transport) {
}
