package com.enterprise.cs.conversation.api;

import com.enterprise.cs.commons.constant.CsConstants;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话记忆 Advisor（自研，《03》§4 order=400：conversation 提供、ai-core 装配——本类实现
 * Spring AI 外部接口，装配侧按外部类型注入，不产生模块间编译依赖）。
 * M0批4 雏形 = 窗口读拼（《03》§3.2 第 3 步）：经 Advisor 上下文携带的 sessionId 读取
 * 近期 USER/AI 消息（排除本轮入站消息），旧→新前置于请求指令之前；摘要/槽位/预算压缩
 * 与写回事件 M1批1 接续。无会话上下文（非会话链路）原样透传。
 */
@Component
public class SessionMemoryAdvisor implements StreamAdvisor {

    public static final String NAME = "SessionMemoryAdvisor";

    /** 《03》§4 唯一权威链序：SessionMemoryAdvisor = 400。 */
    public static final int ORDER = 400;

    private final ConversationPort conversation;
    private final int window;

    public SessionMemoryAdvisor(ConversationPort conversation,
                                @Value("${cs.conversation.memory.window:10}") int window) {
        this.conversation = conversation;
        this.window = window;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Object sessionIdRaw = request.context().get(CsConstants.CTX_SESSION_ID);
        if (!(sessionIdRaw instanceof String sessionId)) {
            return chain.nextStream(request);
        }
        UUID exclude = null;
        Object excludeRaw = request.context().get(CsConstants.CTX_EXCLUDE_MESSAGE_ID);
        if (excludeRaw instanceof String s && !s.isBlank()) {
            exclude = UUID.fromString(s);
        }
        List<ConversationPort.WindowMessage> history =
                conversation.recentWindow(UUID.fromString(sessionId), window, exclude);
        if (history.isEmpty()) {
            return chain.nextStream(request);
        }
        // 《03》§3.2 组装序：[系统提示, (滚动摘要 M1), ...历史窗口, 本轮输入]——
        // 系统提示保持首位，历史插在其后（默认 ChatClient 指令序 = system 在首）。
        List<Message> original = request.prompt().getInstructions();
        int insertAt = !original.isEmpty() && original.get(0) instanceof SystemMessage ? 1 : 0;
        List<Message> combined = new ArrayList<>(original.size() + history.size());
        combined.addAll(original.subList(0, insertAt));
        for (ConversationPort.WindowMessage w : history) {
            combined.add("USER".equals(w.role())
                    ? new UserMessage(w.content())
                    : new AssistantMessage(w.content()));
        }
        combined.addAll(original.subList(insertAt, original.size()));
        return chain.nextStream(request.mutate()
                .prompt(new Prompt(combined, request.prompt().getOptions()))
                .build());
    }
}
