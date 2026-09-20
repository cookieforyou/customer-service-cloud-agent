package com.enterprise.cs.orchestration.app;

import com.enterprise.cs.ai.api.ModelTier;
import com.enterprise.cs.ai.api.RoutingChatModel;
import com.enterprise.cs.commons.constant.CsConstants;
import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.orchestration.api.SupervisorTurnPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * main_supervisor 轮次引擎（《04》§1 表：对话入口，T1，面向用户流式）。
 * M0批4 最小链：代码内系统提示 + RoutingChatModel（T1 直连）+ SessionMemoryAdvisor（窗口读拼）
 * → TOKEN 增量出帧 + completed/failed 计量信号。意图路由/澄清/派生/工具（M1-M2）在此引擎上生长。
 * 落库（cs_turn/cs_message/cs_session_event）不在本模块——驱动侧（channel 适配器）职责。
 * 模型经 ObjectProvider 惰性解析：cs.ai.enabled=false 时上下文照常装配（echo 桩/无模型测试），
 * 运行期未装配则 failed 帧（引擎不可用属配置态而非启动态故障）。
 */
@Service
public class SupervisorTurnEngine implements SupervisorTurnPort {

    private static final Logger log = LoggerFactory.getLogger(SupervisorTurnEngine.class);

    /** 整轮预算（《04》§2：SUPERVISOR 45s；M0批4 取保守 60s 上限，正式预算随 M1 路由前置定案）。 */
    private static final Duration TURN_BUDGET = Duration.ofSeconds(60);

    /**
     * main_supervisor 系统提示（M0批4 代码内形态；M1 移 PromptRepository/Langfuse 托管，《09》§5）。
     * 最小版护栏：身份/语言/边界（不编造、高危动作转人工引导）——完整护栏链（moderation/PII）M1 落 Advisor。
     */
    static final String SYSTEM_PROMPT = """
            你是企业智能客服平台的对话主管，负责直接接待顾客咨询。

            行为准则：
            1. 用简体中文、友好专业的客服语气作答，回答简洁聚焦，一次只解决顾客当前的问题。
            2. 只依据对话上下文与你确知的常识作答；不确定时如实说明并请顾客补充信息，绝不编造政策、订单状态或承诺。
            3. 涉及退款、赔付、改价、账户安全等高风险诉求，先安抚并说明将协助转接人工处理，不要自行承诺处理结果。
            4. 顾客情绪激动时先共情安抚，再回到问题本身。
            """;

    private final ObjectProvider<RoutingChatModel> routingProvider;
    private final StreamAdvisor sessionMemoryAdvisor;

    private volatile ChatClient client;

    public SupervisorTurnEngine(ObjectProvider<RoutingChatModel> routingProvider,
                                StreamAdvisor sessionMemoryAdvisor) {
        this.routingProvider = routingProvider;
        this.sessionMemoryAdvisor = sessionMemoryAdvisor;
    }

    @Override
    public void runTurn(TurnRequest request, TurnFrames frames) {
        long startedAt = System.nanoTime();
        AtomicReference<Usage> usage = new AtomicReference<>();
        try {
            client().prompt()
                    .user(request.userText())
                    .advisors(a -> {
                        a.param(CsConstants.CTX_SESSION_ID, request.sessionId().toString());
                        a.param(CsConstants.CTX_EXCLUDE_MESSAGE_ID, request.messageId().toString());
                    })
                    .stream().chatResponse()
                    .doOnNext(response -> {
                        String delta = delta(response);
                        if (!delta.isEmpty()) {
                            frames.token(delta);
                        }
                        Usage u = response.getMetadata().getUsage();
                        if (u != null && u.getPromptTokens() != null && u.getCompletionTokens() != null) {
                            usage.set(u);   // usage 随末块到达（stream_options.include_usage）
                        }
                    })
                    .blockLast(TURN_BUDGET);
            Usage u = usage.get();
            frames.completed(
                    u != null ? u.getPromptTokens() : null,
                    u != null ? u.getCompletionTokens() : null,
                    (int) Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        } catch (Exception e) {
            log.warn("supervisor turn 失败: turnId={}, tier={}", request.turnId(), ModelTier.T1_PRIMARY, e);
            frames.failed(ErrorCodes.INTERNAL_ERROR, "turn failed");
        }
    }

    private ChatClient client() {
        ChatClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    RoutingChatModel routing = routingProvider.getIfAvailable();
                    if (routing == null) {
                        throw new IllegalStateException(
                                "RoutingChatModel 未装配（cs.ai.enabled=false？）——orchestration 引擎不可用");
                    }
                    client = ChatClient.builder(routing)
                            .defaultSystem(SYSTEM_PROMPT)
                            // Advisor 显式构造注入（《03》§4 装配纪律雏形；M1 钉全链序并关工具自动注册）
                            .defaultAdvisors(List.of(sessionMemoryAdvisor))
                            .build();
                }
                c = client;
            }
        }
        return c;
    }

    /** 增量文本：usage-only 末块无 generation，返回空串。 */
    private static String delta(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text != null ? text : "";
    }
}
