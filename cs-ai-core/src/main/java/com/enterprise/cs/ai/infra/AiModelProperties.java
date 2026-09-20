package com.enterprise.cs.ai.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模型接入配置（《04》§4）。M0批4 仅 T1 必配；T0/T2/T3 载体随 M1 路由矩阵扩展。
 * enabled=false（缺省）时 ai-core 不装配任何模型 Bean——echo 桩/无模型场景零依赖启动。
 */
@ConfigurationProperties("cs.ai")
public record AiModelProperties(
        @DefaultValue("false") boolean enabled,
        Tier t1) {

    /** T1 主对话载体（OpenAI 兼容端点；缺省智谱 GLM，与知识服务同源策略）。 */
    public record Tier(
            @DefaultValue("https://open.bigmodel.cn/api/paas/v4") String baseUrl,
            String apiKey,
            @DefaultValue("glm-5.3-flash") String model,
            @DefaultValue("1.0") Double temperature,
            @DefaultValue("4096") Integer maxTokens) {
    }
}
