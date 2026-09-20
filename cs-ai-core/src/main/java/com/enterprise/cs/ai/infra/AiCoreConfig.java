package com.enterprise.cs.ai.infra;

import com.enterprise.cs.ai.api.ModelTier;
import com.enterprise.cs.ai.api.RoutingChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;

/**
 * 模型装配（《04》§4，Spring AI 2.0.1 形态，源码+姊妹实证核验）：
 * ① OpenAiChatModel 手工装配，baseUrl/apiKey 经 OpenAiChatOptions 传入（异步 client 不继承
 * 预建同步 client 凭证，姊妹坑位同源）；② 不引 chat starter——防自动装配多模型冲突
 * （ChatClientAutoConfiguration 按类型裸注入单 ChatModel，多 Bean 歧义启动失败，坑#04）；
 * ③ 手工模型显式挂 ObservationRegistry（builder 缺省 NOOP，观测断链）；④ 路由 Bean @Primary。
 */
@Configuration
public class AiCoreConfig {

    public static final String BEAN_T1_PRIMARY = "t1PrimaryChatModel";

    /** T1 主模型：密钥缺失快失败（防落入 OpenAI SDK 晦涩凭证异常）。 */
    @Bean(BEAN_T1_PRIMARY)
    @ConditionalOnProperty(name = "cs.ai.enabled", havingValue = "true")
    public ChatModel t1PrimaryChatModel(AiModelProperties props,
                                        ObjectProvider<ObservationRegistry> observationRegistry) {
        var t1 = props.t1();
        if (t1 == null || t1.apiKey() == null || t1.apiKey().isBlank()) {
            throw new IllegalStateException("CS_AI_API_KEY 未配置——T1 主模型不可用（《04》§4；echo 桩场景请保持 cs.ai.enabled=false）");
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(t1.baseUrl())
                .apiKey(t1.apiKey())
                .model(t1.model())
                // 官方推荐采样参数（GLM-5.3-Flash temperature 1.0，姊妹实证）
                .temperature(t1.temperature())
                .maxTokens(t1.maxTokens())
                // 流式末块携带 usage（智谱 OpenAI 兼容端点支持，姊妹实证）——轮次计量 tokens_in/out 依赖
                .streamOptions(OpenAiChatOptions.StreamOptions.builder().includeUsage(true).build())
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    /** 路由模型（应用级模型面）：@Primary 消多 ChatModel 注入歧义（坑#04）。 */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "cs.ai.enabled", havingValue = "true")
    public RoutingChatModel routingChatModel(@Qualifier(BEAN_T1_PRIMARY) ChatModel t1Primary) {
        return new RoutingChatModel(Map.of(ModelTier.T1_PRIMARY, t1Primary), ModelTier.T1_PRIMARY);
    }
}
