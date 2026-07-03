package com.comet.opik.infrastructure.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    String GENERAL_EVENTS = "general_events"; // 用户限制
    String WORKSPACE_EVENTS = "workspace_events"; // 工作区限制
    String SINGLE_TRACING_OPS = "singleTracingOps"; // 单次追踪操作限制

    /**
     * 定义速率限制的自定义桶名称。
     *
     * @return 桶名称
     * <br>
     * 要定义自定义桶名称，请使用以下格式：
     * <br>
     * - 简单桶名称："bucketName"
     * - 带占位符的桶名称："bucketName:{placeholder}"
     * <br>
     * 占位符会被请求上下文中的实际值替换。目前支持以下占位符：
     * <br>
     * - {workspaceId}
     * - {apiKey}
     * - {clientIp}
     * */
    String[] value() default {};

    boolean shouldAffectWorkspaceLimit() default true;
    boolean shouldAffectUserGeneralLimit() default true;
}
