package com.enterprise.cs.channel.api;

import java.util.UUID;

/** webchat 端点 DTO（《08》§2/§3/§4）。阶段：M0批3。 */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** appKey+HMAC 验签换发请求（timestamp=毫秒，sign=HMAC-SHA256(secret, appKey+"\n"+timestamp)）。 */
    public record VisitorTokenRequest(String appKey, long timestamp, String sign, String visitorId) {
    }

    public record VisitorTokenResponse(String token, String visitorId, long expiresInSeconds) {
    }

    public record SendMessageRequest(String text, String channelMsgId) {
    }

    /** duplicate=true 表示幂等重放（《03》§5）；DB 兜底路径 turnId 可能为 null（首签发记录已随缓存过期）。 */
    public record SendMessageView(UUID turnId, UUID messageId, boolean duplicate) {
    }

    public record SessionOpenedView(UUID sessionId) {
    }
}
