package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadConstraints;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Jackson JSON 处理配置。
 *
 * 控制 JSON 反序列化的限制，以防止极大负载（例如 base64 编码的附件）导致内存耗尽。
 */
@Data
@NoArgsConstructor
public class JacksonConfig {

    // 下面字节上限的上界：单个 Java String/数组不能超过 Integer.MAX_VALUE
    // （2^31-1 字节），因此更大的上限无法保护堆，而且几乎总是单位拼写错误。
    private static final long MAX_CONFIGURABLE_BYTES = Integer.MAX_VALUE; // ~2GB (2^31-1)

    /**
     * JSON 反序列化期间单个字符串值的最大大小（字节）。
     * 同时应用于 HTTP 请求和内部 JSON 处理以保持一致。
     *
     * 这确保如果 HTTP 层接受了一个负载，内部处理也能处理它。
     * 附件剥离在初始摄入之后异步进行。
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxStringLength must be at least 1MB") private int maxStringLength = StreamReadConstraints.DEFAULT_MAX_STRING_LEN;

    /**
     * 最大解压文档大小（整个批次），在解析中途强制生效 -> 413；用于捕获一批 {@link #maxStringLength}
     * 无法发现的许多小值。{@code <= 0} = 无限制（因此用自定义的 {@link #isMaxDocumentLengthValid()}
     * 而不是 {@code @Min}/{@code @Max}）。
     */
    @JsonProperty
    private long maxDocumentLength;

    /**
     * 最大请求体大小（压缩后的 {@code Content-Length}），由
     * {@link com.comet.opik.infrastructure.RequestSizeLimitFilter} 在解析前以 413 拒绝——
     * 这与 {@link #maxDocumentLength}（解压后）是不同的维度，因此较小的值是合法的。
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxRequestSizeBytes must be at least 1MB") @Max(value = MAX_CONFIGURABLE_BYTES, message = "maxRequestSizeBytes must be at most 2GB") private long maxRequestSizeBytes;

    @JsonIgnore
    @AssertTrue(message = "maxDocumentLength must be <= 0 (unlimited) or between maxStringLength and 2GB")
    public boolean isMaxDocumentLengthValid() {
        return maxDocumentLength <= 0
                || (maxDocumentLength >= maxStringLength && maxDocumentLength <= MAX_CONFIGURABLE_BYTES);
    }

    // 不对 maxRequestSizeBytes 与 maxDocumentLength 做跨字段检查：它们是不同的维度（压缩 vs 解压），
    // 因此 request < document 是合法的（config-test.yml 依赖这一点来测试 413 路径）。
}
