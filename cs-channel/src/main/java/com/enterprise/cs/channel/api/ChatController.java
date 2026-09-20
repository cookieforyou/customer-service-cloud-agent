package com.enterprise.cs.channel.api;

import com.enterprise.cs.channel.api.ChatDtos.SessionOpenedView;
import com.enterprise.cs.channel.api.ChatDtos.SendMessageRequest;
import com.enterprise.cs.channel.api.ChatDtos.SendMessageView;
import com.enterprise.cs.channel.api.ChatDtos.VisitorTokenRequest;
import com.enterprise.cs.channel.api.ChatDtos.VisitorTokenResponse;
import com.enterprise.cs.channel.app.ChatService;
import com.enterprise.cs.commons.api.dto.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * webchat 渠道端点（《08》§2/§4）：访客令牌换发（appKey 验签，permitAll）、
 * 会话/消息 REST 与 SSE 流（Last-Event-ID 断线补发）。阶段：M0批3。
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 站点 appKey+HMAC 验签 → 平台自签匿名访客 JWT（《08》§3；permitAll）。 */
    @PostMapping("/visitor-tokens")
    public ApiResponse<VisitorTokenResponse> issueVisitorToken(
            @RequestBody VisitorTokenRequest request) {
        return ApiResponse.ok(chatService.issueVisitorToken(request));
    }

    @PostMapping("/sessions")
    public ApiResponse<SessionOpenedView> openSession(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(chatService.openSession(AuthClaims.of(jwt)));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<SendMessageView> sendMessage(
            @PathVariable UUID sessionId,
            @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(chatService.sendMessage(sessionId, request, AuthClaims.of(jwt)));
    }

    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventIdHeader,
            @RequestParam(value = "lastEventId", required = false) Long lastEventIdParam,
            @AuthenticationPrincipal Jwt jwt) {
        // 浏览器 EventSource 自动重连带 Last-Event-ID 头；手动补发（无法自定义头）走 query 参数
        long after = Math.max(lastEventIdHeader == null ? 0L : lastEventIdHeader,
                lastEventIdParam == null ? 0L : lastEventIdParam);
        return chatService.stream(sessionId, after, AuthClaims.of(jwt));
    }
}
